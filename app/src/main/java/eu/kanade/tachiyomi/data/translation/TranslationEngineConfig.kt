package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.ModelSet
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 共用的「引擎建構參數」組裝（[PageTranslator.translateChapter] 與即時翻譯的 [TranslationEngineService] 共用）。
 *
 * 抽這層的理由：模型解析（modelsDir/resolveNcnnRole/ensureLocal）＋ 60 行 [EngineConfig] 是「常調的旋鈕」，
 * 散成兩份必飄移。集中在此 → 兩條路徑（離線整章翻 / 即時逐頁翻）永遠拿到相同設定，調一處即生效。
 *
 * 模型格式（2026-09 起）：**三顆全 NCNN**（ONNX Runtime 已整個從引擎拔掉）——偵測 DBNet / 去字 AOT 各一對 `.param`+`.bin`；
 * OCR 48px CTC 是**兩份 `.param` 共用一份 `.bin`**（base 全精度 + `_mixed` 混合精度，選用邏輯在引擎，見 [resolveOcrRole]）。
 * `.onnx` 只剩 [rolePresent] 的寬鬆存在檢查還認得（讓舊 int8 OCR 使用者被判「過時」而非「缺檔」），引擎不再載它。
 *
 * 夜讀（選配，manifest v5 role `charseg`）：人物分割兩顆 NCNN——`manga_seg_s.ncnn.*`（YOLO11-seg）＋ `cartoonseg.ncnn.*`
 * （RTMDet-Ins），只給引擎 NightReadRenderer 用、**不進翻譯就緒/過時判定**（[modelsResolvable]/[hasAllModels]/[modelsOutdated]
 * 都不看它們）。解析走 [resolveCharSegModels]（不放進 [resolveModelSet]：翻譯引擎每次建構都不必為它付 SAF 複製的代價）、
 * 存在查 [charSegResolvable]，狀態頁另立一列（[modelPresence] 的 optional 列）。
 *
 * 此物件**不持有任何引擎/模型狀態**——只把 [TranslationPreferences] + 儲存位置 → 純資料（[ModelSetBundle]/[EngineConfig]）。
 * 引擎生命週期、Mutex、close() 由各呼叫端自管。
 */
object TranslationEngineConfig {

    private const val MODELS_DIR = "models"
    private const val ALPHABET = "models/alphabet-all-v5.txt"

    /**
     * OCR 角色 base `.param` 的檔名關鍵字（`ocr_48px_ctc.ncnn.param`，manifest models-v4）。
     * ★ 刻意含 `.ncnn`、不能只寫 `ocr`：同目錄還有 `ocr_48px_ctc_mixed.ncnn.param`（混合精度、共用同一份 `.bin`），
     * 只用 `ocr` 會兩份都命中、撈到 mixed 時 [resolveNcnnRole]/[ncnnResolvable] 由 `.param` 名推出的
     * `ocr_48px_ctc_mixed.ncnn.bin` 並不存在 → 誤判缺檔。含 `.ncnn` 後 `_mixed.ncnn` 那份就對不上。
     */
    private const val OCR_NCNN_BASE = "ocr_48px_ctc.ncnn"

    /** 引擎 `Ocr.pickParam` 找 mixed param 的後綴（`<name>.ncnn.param` → `<name>_mixed.ncnn.param`）；此處只用來推檔名。 */
    private const val OCR_MIXED_SUFFIX = "_mixed.ncnn.param"

    /** 偵測器 DBNet 的檔名關鍵字（`dbnet_detect.ncnn.param`，models-v3）；翻譯與夜讀共用同一顆。 */
    private const val DETECTOR_KEYWORD = "dbnet"

    /**
     * 夜讀人物分割 YOLO11-seg 的檔名關鍵字（`manga_seg_s.ncnn.param`，manifest models-v5 role `charseg`）。
     * 與引擎 `ModelSet.fromDir` 的 find 關鍵字同字（引擎＝真理來源，改一邊要同步）。
     */
    private const val CHARSEG_YOLO = "manga_seg"

    /** 夜讀人物分割 CartoonSegmentation（RTMDet-Ins）的檔名關鍵字（`cartoonseg.ncnn.param`，同上）。 */
    private const val CHARSEG_CSEG = "cartoonseg"

    private val storagePreferences: StoragePreferences = Injekt.get()

    /** [resolveModelSet] 的回傳：三顆模型的本機路徑 [ModelSet] + OCR 字元表（CTC 解碼用）。 */
    data class ModelSetBundle(val models: ModelSet, val alphabet: List<String>)

    /**
     * [modelPresence] 的一列：[label] 顯示名、[present] 有無檔、[optional] 是否選配。
     * optional=true（目前只有夜讀人物分割）＝缺了也**不算**「模型未齊」：UI 組整體狀態時要把它排除在缺檔判定外、
     * 只在該列自己顯示 ✗。用旗標而非讓 UI 比對 label 字串（label 是 i18n、比字串會隨語系壞）。
     */
    data class RolePresence(val label: String, val present: Boolean, val optional: Boolean = false)

    /**
     * mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把 NCNN 模型檔放這（BYOM）：
     * 偵測/去字各 `.param`+`.bin`、OCR 兩份 `.param`（base + `_mixed`）+ 一份 `.bin`。
     */
    fun modelsDir(context: Context): UniFile? {
        val base = storagePreferences.baseStorageDirectory.get().takeIf { it.isNotBlank() } ?: return null
        return UniFile.fromUri(context, base.toUri())?.findFile(MODELS_DIR)
    }

    /**
     * 在 [dir] 找檔名含任一 [keywords] 且以 [ext] 結尾的第一個檔。
     * 解析用 ".param"（三顆全 NCNN）；".onnx" 只剩 [rolePresent] 寬鬆存在檢查用來認舊 int8 OCR。
     */
    fun findModel(dir: UniFile, ext: String, vararg keywords: String): UniFile? =
        dir.listFiles()?.firstOrNull { f ->
            val n = f.name?.lowercase() ?: return@firstOrNull false
            n.endsWith(ext) && keywords.any { n.contains(it) }
        }

    /** 自動下載落點：app 私有 `filesDir/models/`（[ModelDownloadManager] 寫這、引擎直接從此載入，免 SAF）。 */
    fun downloadedDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    /** 在自動下載區找符合 [keywords] 且 [ext] 結尾的檔（已是本機 [File]、免複製）。 */
    private fun downloadedModel(context: Context, ext: String, vararg keywords: String): File? =
        downloadedDir(context).takeIf { it.isDirectory }?.listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            n.endsWith(ext) && keywords.any { n.contains(it) }
        }

    private fun presentExt(context: Context, saf: UniFile?, ext: String, vararg keywords: String): Boolean =
        downloadedModel(context, ext, *keywords) != null || (saf != null && findModel(saf, ext, *keywords) != null)

    /**
     * 某角色是否**有檔**（寬鬆：NCNN `.param` 或退役的 `.onnx` 都算；自動下載區 或 SAF BYOM 區皆算）。
     *
     * ★ 這是全檔唯一還看 `.onnx` 的地方，而且是**刻意保留**的：引擎已不載 ONNX（三顆全 NCNN），但 v3 以前的自動下載會留
     * `ocr_int8.onnx` 在 `filesDir/models`——若這裡不認它，舊使用者升級後 OCR 角色被判「缺檔」、[hasAllModels]=false →
     * [modelsOutdated] 永遠不觸發、設定頁只會說「未下載」而非「舊版·請重新下載」。認了它：有檔(loose) 但
     * [modelsResolvable](strict) 找不到 NCNN base `.param`+`.bin` → 判過時 → 提示更新；下載 v4 後 [ModelDownloadManager]
     * 的 prune 會把不在 manifest 的 `ocr_int8.onnx` 一併清掉，這條路徑就自然消失。
     */
    private fun rolePresent(context: Context, saf: UniFile?, vararg keywords: String): Boolean =
        presentExt(context, saf, ".param", *keywords) || presentExt(context, saf, ".onnx", *keywords)

    /** 解析 NCNN 角色 → 本機 `.param` 路徑，並確保同名 `.bin` 也在本機（引擎由 .param 推 .bin）。缺 .param 或 .bin＝null。 */
    private fun resolveNcnnRole(context: Context, saf: UniFile?, vararg keywords: String): String? {
        // 自動下載區：.param 與 .bin 都已在 filesDir/models（ModelDownloader 逐檔下）→ 直接用 .param 路徑。
        downloadedModel(context, ".param", *keywords)?.let { return it.absolutePath }
        // SAF BYOM：複製 .param + 同名 .bin 到 filesDir。
        val paramU = saf?.let { findModel(it, ".param", *keywords) } ?: return null
        val binName = (paramU.name ?: return null).removeSuffix(".param") + ".bin"
        val binU = saf.findFile(binName) ?: return null // .bin 必須在旁
        ensureLocal(context, binU)
        return ensureLocal(context, paramU)
    }

    /**
     * NCNN 角色是否**可被引擎載入**（strict）：`.param` ＋同名 `.bin` 都在。鏡射 [resolveNcnnRole] 的 `.bin` 要求，
     * 但**不做 [ensureLocal] 複製副作用**（純查存在，給 UI 狀態 / isReady 用、可頻繁呼叫）。
     */
    private fun ncnnResolvable(context: Context, saf: UniFile?, vararg keywords: String): Boolean {
        downloadedModel(context, ".param", *keywords)?.let { p ->
            if (File(p.parentFile, p.name.removeSuffix(".param") + ".bin").exists()) return true
        }
        val paramU = saf?.let { findModel(it, ".param", *keywords) } ?: return false
        val binName = (paramU.name ?: return false).removeSuffix(".param") + ".bin"
        return saf.findFile(binName) != null
    }

    /**
     * 解析 OCR 角色 → 本機 **base** `.param` 路徑（缺 base `.param` 或 `.bin`＝null）。
     *
     * OCR 跟偵測/去字不同：**兩份 `.param` 共用一份 `.bin`**——base（`ocr_48px_ctc.ncnn.param`，全精度）＋
     * `ocr_48px_ctc_mixed.ncnn.param`（backbone fp16、transformer/char_pred fp32；真機小假名讀對率＝fp32、OCR 時間比舊 int8
     * 快 ~23%）。引擎 [li.joye.yakuyomi.engine.Ocr] 只收 **base** 路徑，會自己在**同目錄**找 `<name>_mixed.ncnn.param`，
     * 且只在 fp16 storage 開＋CPU 有 asimdhp 時才用它（否則 mixed 裡的 Cast 層會把 fp32 讀成垃圾）；找不到或條件不符
     * 一律退回 base、不會壞。所以 mixed 對「模型齊不齊」是**選配**，這裡不驗它存在、只確保「若有就在 base 旁」：
     * - 自動下載區：v4 三檔本就同落 `filesDir/models`，base 從那裡解析到就免處理。
     * - SAF BYOM：[resolveNcnnRole] 只把 base+`.bin` 複製到 `filesDir`，mixed 得在此順手 [ensureLocal] 到同一目錄，
     *   否則引擎在本機找不到、永遠跑全精度（能用但慢、使用者不會察覺）。
     * mixed 檔名不用關鍵字撈，而是照引擎 `Ocr.pickParam` 的推法從 base 名推（去 `.ncnn.param` 加 [OCR_MIXED_SUFFIX]），
     * 複製到的必是引擎會找的那個檔。
     */
    private fun resolveOcrRole(context: Context, saf: UniFile?): String? {
        val base = resolveNcnnRole(context, saf, OCR_NCNN_BASE) ?: return null
        // base 落在自動下載區 → mixed（若有下載）已在同目錄；只有 SAF 來源（ensureLocal 落 filesDir 根）才要把 mixed 搬過去。
        if (File(base).parentFile?.absolutePath == downloadedDir(context).absolutePath || saf == null) return base
        val mixedName = File(base).name.removeSuffix(".ncnn.param").removeSuffix(".param") + OCR_MIXED_SUFFIX
        saf.findFile(mixedName)?.let { ensureLocal(context, it) }
        return base
    }

    /**
     * 模型是否**真的能被引擎載入**（strict，「可用」的單一真理來源）：三顆都要 NCNN `.param`＋同名 `.bin`
     * （OCR 只驗 base `ocr_48px_ctc.ncnn.*`；`_mixed` 選配、引擎自動退回，見 [resolveOcrRole]）。
     * 與 [resolveModelSet] 的解析要求逐條對齊，但不做複製副作用。
     *
     * 這是修「舊模型靜默失敗」的核心：[hasAllModels]/[modelPresence] 是**寬鬆存在**（`.onnx`/舊 LaMa 也算「有檔」），
     * 但引擎實際只吃 `.param`——舊 v1（ORT 偵測 + LaMa）或 v3（int8 `.onnx` OCR）→ [modelsResolvable]=false →
     * isReady 據此擋下（不啟動翻譯、§11 安全），狀態頁據此把「齊全」改判「舊版·請重新下載」。
     * BYOM 放了 `.param` 卻漏 `.bin` 的半套也會被這裡擋下（堵住「顯示齊全卻 build 失敗」）。
     *
     * ★ 夜讀人物分割（charseg）**刻意不在此**：它是選配、翻譯不依賴它，缺了不該把翻譯判成不可用（見 [charSegResolvable]）。
     */
    fun modelsResolvable(context: Context): Boolean {
        val saf = modelsDir(context)
        return ncnnResolvable(context, saf, DETECTOR_KEYWORD) &&
            ncnnResolvable(context, saf, OCR_NCNN_BASE) &&
            ncnnResolvable(context, saf, "aot")
    }

    /**
     * 三個角色是否**各有一個模型檔存在**（寬鬆：`.param` 或 `.onnx`、含退役格式都算）。
     * ★這只代表「有檔」、**不代表引擎載得動**——能不能真的翻由 [modelsResolvable]（strict）判、isReady 也吃那個。
     * 本函式的用途只剩「湊齊了嗎」＋餵給 [modelsOutdated]（有齊全的舊檔但格式過時 → 提示更新）。
     * 去字認 `aot`（v2）**與** `lama`（退役 v1）→ 舊 LaMa 使用者也算「有去字檔」，過時提示才觸發得了（見 [modelsOutdated]）。
     * OCR 關鍵字**維持寬鬆的 `ocr`**（不用 [OCR_NCNN_BASE]）：v3 的 `ocr_int8.onnx` 也要算「有檔」，理由同 [rolePresent]。
     * 夜讀人物分割（charseg）不算在「三個角色」內——選配、不影響翻譯的「齊不齊」（見 [charSegResolvable]）。
     */
    fun hasAllModels(context: Context): Boolean {
        val saf = modelsDir(context)
        return rolePresent(context, saf, DETECTOR_KEYWORD) &&
            rolePresent(context, saf, "ocr") &&
            rolePresent(context, saf, "aot", "lama")
    }

    /**
     * 自架 / 自訂 provider（sakura/custom）是否缺 API base。給 isReady 擋下——base 空＝聊天端點空＝必失敗，
     * 與其讓整章標 Failed，不如 isReady 先回 false（不啟動）。內建端點的 provider 一律回 false（不受影響）。
     */
    fun isProviderBaseMissing(prefs: TranslationPreferences): Boolean {
        val preset = LlmProviders.byId(prefs.provider.get())
        return preset.baseEditable && prefs.apiBase.get().isBlank()
    }

    /**
     * 各模型「是否存在」（逐列，給設定頁顯示模型狀態 / BYOM 排錯 / 診斷「未啟動」）。
     * **只查存在、不驗 checksum**——模型權重會更新（m-i-t / Koharu 後續版本），checksum 會誤判成「損毀」；BYOM 也允許換版。
     *
     * 前三列（偵測/OCR/去字）是翻譯必要角色、寬鬆存在（與 [hasAllModels] 同一套判準）；末列「夜讀」是**選配**
     * （[RolePresence.optional]=true），用 strict 的 [charSegResolvable]（`.param`+`.bin` 都要——它沒有退役格式要認，
     * 寬鬆版沒意義）。UI 組整體狀態時只看非選配列：夜讀缺檔只在自己那列顯示 ✗、不把整體判成「未齊」，
     * 也不進 [modelsOutdated]（那個只吃 [hasAllModels]/[modelsResolvable]）。
     */
    fun modelPresence(context: Context): List<RolePresence> {
        val saf = modelsDir(context)
        return listOf(
            RolePresence(
                label = context.stringResource(MR.strings.model_role_detect),
                present = rolePresent(context, saf, DETECTOR_KEYWORD),
            ),
            RolePresence(
                label = context.stringResource(MR.strings.model_role_ocr),
                present = rolePresent(context, saf, "ocr"),
            ),
            RolePresence(
                label = context.stringResource(MR.strings.model_role_inpaint),
                present = rolePresent(context, saf, "aot", "lama"),
            ),
        )
    }

    /**
     * 夜讀那組模型的逐列狀態（設定 › 夜讀 的模型狀態）：偵測器（與翻譯共用，寬鬆存在）＋人物分割兩顆（strict：
     * `.param`+`.bin`）。人物分割「至少一顆」就能跑（[charSegResolvable]），兩顆都標 optional 讓 UI 不把單顆缺判成整體未齊。
     */
    fun nightModelPresence(context: Context): List<RolePresence> {
        val saf = modelsDir(context)
        return listOf(
            RolePresence(
                label = context.stringResource(MR.strings.model_role_detect_shared),
                present = rolePresent(context, saf, DETECTOR_KEYWORD),
            ),
            RolePresence(
                label = context.stringResource(MR.strings.model_role_charseg_yolo),
                present = ncnnResolvable(context, saf, CHARSEG_YOLO),
                optional = true,
            ),
            RolePresence(
                label = context.stringResource(MR.strings.model_role_charseg_cseg),
                present = ncnnResolvable(context, saf, CHARSEG_CSEG),
                optional = true,
            ),
        )
    }

    /** 模型分組（下載／刪除以組為單位；同一個資料夾、同一份 manifest，偵測器兩組共用）。 */
    enum class ModelGroup { TRANSLATION, NIGHT }

    /** manifest role → 屬於哪組（偵測器兩組都算）。 */
    fun groupsOfRole(role: String): Set<ModelGroup> = when (role) {
        "detector" -> setOf(ModelGroup.TRANSLATION, ModelGroup.NIGHT)
        "charseg" -> setOf(ModelGroup.NIGHT)
        else -> setOf(ModelGroup.TRANSLATION)
    }

    /** 本機檔名 → 屬於哪組（刪除用：不需要網路撈 manifest）。偵測器兩組都算；認不出的檔不屬任何組（不刪）。 */
    fun groupsOfFile(name: String): Set<ModelGroup> {
        val n = name.lowercase()
        return when {
            n.contains(DETECTOR_KEYWORD) -> setOf(ModelGroup.TRANSLATION, ModelGroup.NIGHT)
            n.contains(CHARSEG_YOLO) || n.contains(CHARSEG_CSEG) -> setOf(ModelGroup.NIGHT)
            n.contains("ocr") || n.contains("aot") || n.contains("lama") -> setOf(ModelGroup.TRANSLATION)
            else -> emptySet()
        }
    }

    /**
     * 模型「齊但過時」＝三個角色**各有檔**（[hasAllModels] 寬鬆為真）**但引擎載不動**（[modelsResolvable] 為假）。
     * 典型＝升級後沿用舊模型：v1（ORT 偵測 + LaMa `.onnx`，無 NCNN `.param`）、或 v3（OCR 仍是 `ocr_int8.onnx`，
     * 缺 NCNN `ocr_48px_ctc.ncnn.*`）——這正是「靜默失敗」要救的情境。
     *
     * 為何這樣寫（改由可解析性驅動、不再自己重查 `.param`）：舊版把判準綁在「hasAllModels 且缺 `.param`」，
     * 但去字角色以前只認 `aot`、認不到舊 `lama` → hasAllModels 恆 false → 這個旗標對它唯一該救的族群**永遠不觸發**（死碼）。
     * 現在 hasAllModels 補認 `lama`、判準改成「有檔(loose) 但 build 不出來(strict)」，任何「看得到卻用不了」的組合
     * （v1 LaMa、v1 ONNX 偵測、v3 int8 ONNX OCR、BYOM 缺 `.bin`）都會被判過時 → 設定頁顯示「舊版·請重新下載」、
     * 下載鈕標「更新模型」。
     * 夜讀人物分割（charseg）不參與：兩邊輸入（[hasAllModels]/[modelsResolvable]）都不含它，缺夜讀模型永遠不會被判「過時」。
     */
    fun modelsOutdated(context: Context): Boolean = hasAllModels(context) && !modelsResolvable(context)

    /**
     * 解析三顆模型 → 本機路徑 [ModelSet] + 載入 OCR 字元表。缺任一顆模型回 null（呼叫端應略過翻譯）。
     *
     * SAF 模型先串流複製到 filesDir（[ensureLocal]，off-heap 路徑載入，避開 512MB JVM heap OOM；§10）。
     * 複製在背景執行緒（呼叫端的 suspend translate 內）發生、不卡 UI。
     */
    fun resolveModelSet(context: Context): ModelSetBundle? {
        val saf = modelsDir(context)
        // 三顆全 NCNN（ONNX Runtime 已整個拔掉）：DBNet 偵測 + 48px CTC OCR（混合精度，base param 給引擎、mixed 由它自選）
        // + AOT 去字。
        val ocr = resolveOcrRole(context, saf) ?: return null
        val detNcnn = resolveNcnnRole(context, saf, DETECTOR_KEYWORD) ?: return null
        val aotNcnn = resolveNcnnRole(context, saf, "aot") ?: return null
        val alphabet = context.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
        // 夜讀人物分割（ModelSet.charSegYoloNcnn/charSegCsegNcnn）刻意留 null：翻譯引擎用不到，
        // 不讓每次建引擎都為選配模型付 SAF 複製（BYOM 最多 ~147MB）；夜讀端自己走 resolveCharSegModels。
        return ModelSetBundle(
            ModelSet(ocr = ocr, detectorNcnn = detNcnn, aotInpainterNcnn = aotNcnn),
            alphabet,
        )
    }

    /**
     * 解析去字模型路徑（給重繪等「只需去字」的路徑用）：NCNN AOT `.param`（同時確保 `.bin` 在本機）。缺回 null。
     */
    fun resolveInpaintModel(context: Context): String? = resolveNcnnRole(context, modelsDir(context), "aot")

    /**
     * 解析偵測模型路徑（給夜讀等「只需偵測」的路徑用）：NCNN DBNet `.param`（同時確保 `.bin` 在本機）。缺回 null。
     * 與翻譯用的是同一顆（[resolveModelSet] 的 detectorNcnn）；夜讀在引擎 NightReadRenderer 拿它找文字行、決定哪些泡泡要變暗。
     */
    fun resolveDetectorModel(context: Context): String? =
        resolveNcnnRole(context, modelsDir(context), DETECTOR_KEYWORD)

    /**
     * 偵測器是否可載（純查存在、**無** [resolveDetectorModel] 的 SAF 複製副作用、不拋）：給自動夜讀 gate 與 UI 預檢用——
     * 那些呼叫端（下載完成 hook、drain 成功分支、reader）沒有 try/catch 罩著，也不該為了一個 gate 複製 ~100MB 的 .bin。
     */
    fun detectorResolvable(context: Context): Boolean = ncnnResolvable(context, modelsDir(context), DETECTOR_KEYWORD)

    /**
     * 解析夜讀人物分割模型 → (yolo `.param`, cseg `.param`) 本機路徑，各自缺＝null（SAF BYOM 會連同名 `.bin` 複製到本機）。
     * 兩顆都是選配：引擎 NightReadRenderer.charSegmenter 收到一顆 null 會退化成單顆、兩顆都 null 才回 null（夜讀不可用）——
     * 所以這裡**不**因缺一顆就整體回 null，把配方（聯集 > 單顆）交給引擎決定。
     * 有複製副作用（同 [resolveNcnnRole]），只在真要建 CharSegmenter 時呼叫；純查存在用 [charSegResolvable]。
     */
    fun resolveCharSegModels(context: Context): Pair<String?, String?> {
        val saf = modelsDir(context)
        return resolveNcnnRole(context, saf, CHARSEG_YOLO) to resolveNcnnRole(context, saf, CHARSEG_CSEG)
    }

    /**
     * 夜讀人物分割是否**可用**（strict、無複製副作用）：yolo 或 cseg 至少一顆 `.param`＋同名 `.bin` 在（自動下載區或 SAF）。
     * 與 [resolveCharSegModels] 的要求逐條對齊（那邊至少解析到一顆非 null ⇔ 這裡 true）。
     * **不影響**翻譯就緒（isReady）與 [modelsOutdated]——夜讀是選配；給設定頁狀態列（[modelPresence] 的 optional 列）
     * 與 TranslationManager 排夜讀工作前的預檢用（缺＝該工作標 ERROR、翻譯照常）。
     */
    fun charSegResolvable(context: Context): Boolean {
        val saf = modelsDir(context)
        return ncnnResolvable(context, saf, CHARSEG_YOLO) || ncnnResolvable(context, saf, CHARSEG_CSEG)
    }

    /** 去字方法字串 → 引擎 method（boxfill／aot）；實作在 [EngineConfigMapping.mapInpaintMethod]（純映射，下載/即時/重繪共用）。 */
    fun mapInpaintMethod(methodRaw: String): String = EngineConfigMapping.mapInpaintMethod(methodRaw)

    /**
     * 去字法品質排名（高＝品質好）。用於「改去字法後升級重繪」（[TranslationManager.reRenderAllUpgradable]）的
     * 向上/向下判斷：新 rank ≥ 已存 rank 才重繪（升級或持平套排版）、新 rank < 已存則保留（不降級＝保留最好結果）。
     * v2 兩門別：original/無＝0 ＜ boxfill（快速去字）＝1 ＜ 其餘（AI 去字＝純 aot；舊 auto_aot、auto_whole、lama 系列同級）＝2。
     */
    fun inpaintMethodRank(method: String?): Int = when (method) {
        "original", null, "" -> 0
        "boxfill" -> 1
        else -> 2
    }

    /**
     * 用 [prefs] 組出完整 [EngineConfig]（偵測/OCR/翻譯/去字/排版）；實作在 [EngineConfigMapping.buildEngineConfig]
     * （純映射，重繪的去字／排版設定也從那裡拿同一份）。
     *
     * @param methodRaw 去字方法原始字串（[TranslationPreferences.inpaintMethod] 或即時翻的
     *   [TranslationPreferences.liveInpaintMethod]）。
     */
    fun buildEngineConfig(prefs: TranslationPreferences, methodRaw: String): EngineConfig =
        EngineConfigMapping.buildEngineConfig(prefs, methodRaw)

    /** SAF 模型串流複製到 filesDir（64KB、不佔 JVM heap），回傳路徑；已存在且同大小則跳過。 */
    fun ensureLocal(context: Context, doc: UniFile): String {
        val name = doc.name ?: "model.onnx"
        val out = File(context.filesDir, name)
        if (out.exists() && out.length() == doc.length()) return out.absolutePath
        context.contentResolver.openInputStream(doc.uri)!!.use { input ->
            out.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return out.absolutePath
    }
}
