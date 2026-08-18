package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import li.joye.yakuyomi.engine.DetectorConfig
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslatorConfig
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
 * 抽這層的理由：模型解析（modelsDir/findOnnx/ensureLocal）＋ 60 行 [EngineConfig] 是「常調的旋鈕」，
 * 散成兩份必飄移。集中在此 → 兩條路徑（離線整章翻 / 即時逐頁翻）永遠拿到相同設定，調一處即生效。
 *
 * 此物件**不持有任何引擎/模型狀態**——只把 [TranslationPreferences] + 儲存位置 → 純資料（[ModelSetBundle]/[EngineConfig]）。
 * 引擎生命週期、Mutex、close() 由各呼叫端自管。
 */
object TranslationEngineConfig {

    private const val MODELS_DIR = "models"
    private const val ALPHABET = "models/alphabet-all-v5.txt"

    private val storagePreferences: StoragePreferences = Injekt.get()

    /** [resolveModelSet] 的回傳：三顆模型的本機路徑 [ModelSet] + OCR 字元表（CTC 解碼用）。 */
    data class ModelSetBundle(val models: ModelSet, val alphabet: List<String>)

    /** mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把 3 顆 onnx 放這（BYOM）。 */
    fun modelsDir(context: Context): UniFile? {
        val base = storagePreferences.baseStorageDirectory.get().takeIf { it.isNotBlank() } ?: return null
        return UniFile.fromUri(context, base.toUri())?.findFile(MODELS_DIR)
    }

    /** 在 [dir] 找出檔名含任一 [keywords]（不分大小寫）且 `.onnx` 結尾的第一個檔。 */
    fun findOnnx(dir: UniFile, vararg keywords: String): UniFile? =
        dir.listFiles()?.firstOrNull { f ->
            val n = f.name?.lowercase() ?: return@firstOrNull false
            n.endsWith(".onnx") && keywords.any { n.contains(it) }
        }

    /** 在 [dir] 找檔名含任一 [keywords] 且以 [ext] 結尾的第一個檔（NCNN 用 ".param"、ORT 用 ".onnx"）。 */
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

    /** 某角色是否存在（先 NCNN `.param`、再 ORT `.onnx`；自動下載區 或 SAF BYOM 區皆算）。 */
    private fun rolePresent(context: Context, saf: UniFile?, vararg keywords: String): Boolean =
        presentExt(context, saf, ".param", *keywords) || presentExt(context, saf, ".onnx", *keywords)

    /** 解析 ORT `.onnx` 角色 → 本機路徑：自動下載區直接用、否則 SAF + [ensureLocal] 複製。缺＝null。 */
    private fun resolveOnnxRole(context: Context, saf: UniFile?, vararg keywords: String): String? {
        downloadedModel(context, ".onnx", *keywords)?.let { return it.absolutePath }
        val u = saf?.let { findModel(it, ".onnx", *keywords) } ?: return null
        return ensureLocal(context, u)
    }

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
     * 模型是否**真的能被引擎載入**（strict，「可用」的單一真理來源）：偵測/去字要 NCNN `.param`＋同名 `.bin`、OCR 要 `.onnx`。
     * 與 [resolveModelSet] 的解析要求逐條對齊，但不做複製副作用。
     *
     * 這是修「舊模型靜默失敗」的核心：[hasAllModels]/[modelPresence] 是**寬鬆存在**（`.onnx`/舊 LaMa 也算「有檔」），
     * 但 v2 引擎實際只吃 `.param`——舊 v1（ORT 偵測 + LaMa）→ [modelsResolvable]=false → isReady 據此擋下（不啟動翻譯、§11 安全），
     * 狀態頁據此把「齊全」改判「舊版·請重新下載」。BYOM 放了 `.param` 卻漏 `.bin` 的半套也會被這裡擋下（堵住「顯示齊全卻 build 失敗」）。
     */
    fun modelsResolvable(context: Context): Boolean {
        val saf = modelsDir(context)
        return ncnnResolvable(context, saf, "dbnet") &&
            presentExt(context, saf, ".onnx", "ocr") &&
            ncnnResolvable(context, saf, "aot")
    }

    /**
     * 三個角色是否**各有一個模型檔存在**（寬鬆：`.param` 或 `.onnx`、含退役格式都算）。
     * ★這只代表「有檔」、**不代表 v2 引擎載得動**——能不能真的翻由 [modelsResolvable]（strict）判、isReady 也吃那個。
     * 本函式的用途只剩「湊齊了嗎」＋餵給 [modelsOutdated]（有齊全的舊檔但格式過時 → 提示更新）。
     * 去字認 `aot`（v2）**與** `lama`（退役 v1）→ 舊 LaMa 使用者也算「有去字檔」，過時提示才觸發得了（見 [modelsOutdated]）。
     */
    fun hasAllModels(context: Context): Boolean {
        val saf = modelsDir(context)
        return rolePresent(context, saf, "dbnet") &&
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
     * 各模型「是否存在」（逐顆，給設定頁顯示模型狀態 / BYOM 排錯 / 診斷「未啟動」）。
     * **只查存在、不驗 checksum**——模型權重會更新（m-i-t / Koharu 後續版本），checksum 會誤判成「損毀」；BYOM 也允許換版。
     */
    fun modelPresence(context: Context): List<Pair<String, Boolean>> {
        val saf = modelsDir(context)
        return listOf(
            context.stringResource(MR.strings.model_role_detect) to rolePresent(context, saf, "dbnet"),
            context.stringResource(MR.strings.model_role_ocr) to rolePresent(context, saf, "ocr"),
            context.stringResource(MR.strings.model_role_inpaint) to rolePresent(context, saf, "aot", "lama"),
        )
    }

    /**
     * 模型「齊但過時」＝三個角色**各有檔**（[hasAllModels] 寬鬆為真）**但引擎載不動**（[modelsResolvable] 為假）。
     * 典型＝升級到引擎 v2 後沿用舊 v1 模型（ORT 偵測 + LaMa `.onnx`，無 NCNN `.param`）——這正是本次「靜默失敗」要救的情境。
     *
     * 為何這樣寫（改由可解析性驅動、不再自己重查 `.param`）：舊版把判準綁在「hasAllModels 且缺 `.param`」，
     * 但去字角色以前只認 `aot`、認不到舊 `lama` → hasAllModels 恆 false → 這個旗標對它唯一該救的族群**永遠不觸發**（死碼）。
     * 現在 hasAllModels 補認 `lama`、判準改成「有檔(loose) 但 build 不出來(strict)」，任何「看得到卻用不了」的組合
     * （v1 LaMa、v1 ONNX 偵測、BYOM 缺 `.bin`）都會被判過時 → 設定頁顯示「舊版·請重新下載」、下載鈕標「更新模型」。
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
        // 引擎已收斂成純 NCNN 偵測 + int8 OCR + NCNN AOT 去字（ORT 偵測/去字備援與 LaMa 皆退役移除）。
        val ocr = resolveOnnxRole(context, saf, "ocr") ?: return null
        val detNcnn = resolveNcnnRole(context, saf, "dbnet") ?: return null
        val aotNcnn = resolveNcnnRole(context, saf, "aot") ?: return null
        val alphabet = context.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
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
     * 去字方法字串（[TranslationPreferences.inpaintMethod] / 即時翻的 [TranslationPreferences.liveInpaintMethod] 原始值）
     * → 引擎 method。兩門別：`boxfill`（快速去字·平塗）／其餘＝`aot`（AI 去字·NCNN AOT-GAN 整頁 768·預設）。
     * 集中於此一處映射、下載/即時/重繪共用。
     */
    fun mapInpaintMethod(methodRaw: String): String = if (methodRaw == "boxfill") "boxfill" else "aot"

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
     * 用 [prefs] 組出完整 [EngineConfig]（偵測/OCR/翻譯/去字/排版）。
     *
     * @param methodRaw 去字方法原始字串（[TranslationPreferences.inpaintMethod] 或即時翻的 [TranslationPreferences.liveInpaintMethod]）。
     *                  此處只決定去字 method/wholeImage，其餘參數一律照 [prefs]。
     *
     * 與舊 [PageTranslator.translateChapter] 內聯的 cfg 區塊**逐欄相同**（行為保持）：緒數裝置相依、
     * 進階數值 parse + clamp、改目標語言時清掉內建 few-shot。
     */
    fun buildEngineConfig(prefs: TranslationPreferences, methodRaw: String): EngineConfig {
        // 供應商：解析預設表 → 聊天端點 + 模型（per-provider，見引擎 LlmProviders / 設定頁）。
        // 全 OpenAI 相容（含 Gemini 的 compat 端點）⇒ LlmTranslator 不變，只是換 apiBase/model。
        val preset = LlmProviders.byId(prefs.provider.get())
        val chatUrl = LlmProviders.chatUrlOf(preset, prefs.apiBase.get())
        // 語言對（預設日→繁中）。改目標語言就清掉引擎內建的日→繁中 few-shot，免得範例語言跟新目標衝突、把輸出帶偏。
        val target = prefs.targetLangName.get()
        // LLM 取樣溫度（存字串、parse + clamp 到 0.0–1.0；預設 0.3）。
        val temperature = prefs.temperature.get().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3
        var translatorCfg = TranslatorConfig(
            provider = preset.id,
            model = prefs.model.get().ifBlank { preset.defaultModel },
            // base 空（自架/自訂未填）→ isReady 已擋；萬一漏 → LlmTranslator 拋例外標 Failed（不靜默）。
            apiBase = chatUrl,
            toLangName = target,
            fromLangName = prefs.sourceLangName.get(),
            temperature = temperature,
            // 思考模式（預設關）：欄位形狀 per-provider，由引擎 LlmProviders.requestParams 映射。
            thinking = prefs.thinking.get(),
        )
        if (target != TranslationPreferences.DEFAULT_TARGET_LANG) {
            translatorCfg = translatorCfg.copy(sampleSource = "", sampleTarget = "")
        }

        // 排版方向
        val orient = when (prefs.orientation.get()) {
            "vertical" -> TextOrientation.VERTICAL
            "horizontal" -> TextOrientation.HORIZONTAL
            else -> TextOrientation.AUTO
        }

        // 去字方法（boxfill / aot）
        val method = mapInpaintMethod(methodRaw)

        // OCR 逐行並發度：auto=核數 / 2/4/6/8（concurrent 鎖 true）。真機 8.9s→4.8s。
        val cores = Runtime.getRuntime().availableProcessors()
        val ocrConcurrency = when (val v = prefs.ocrConcurrency.get()) {
            "auto" -> cores
            else -> (v.toIntOrNull() ?: cores).coerceIn(1, 32)
        }

        // 進階數值：存字串、此處 parse + clamp 到值域（超界夾回，不擋存）。
        fun pf(s: String, lo: Float, hi: Float, d: Float) = s.toFloatOrNull()?.coerceIn(lo, hi) ?: d
        fun pi(s: String, lo: Int, hi: Int, d: Int) = s.toIntOrNull()?.coerceIn(lo, hi) ?: d

        return EngineConfig(
            detector = DetectorConfig(
                segThreshold = pf(prefs.segThreshold.get(), 0f, 1f, 0.12f),
                // 進階辨識：偵測輸入銳利化（預設關）+ DBNet 辨識尺寸（768–1536，clamp）。
                detectUnsharp = prefs.detectUnsharp.get(),
                dbnetInputSize = prefs.dbnetSize.get().coerceIn(768, 1536),
            ),
            ocr = OcrConfig(
                minProb = pf(prefs.minProb.get(), 0f, 1f, 0.5f),
                // 跳過狀聲詞 SFX：開→給內建門檻 24（1–50 中段，不讓使用者調數字）、關→0。
                ignoreBubble = if (prefs.ignoreSfx.get()) 24 else 0,
                // 進階辨識：OCR 裁切外擴（0–12，clamp）+ 內插法（bicubic/bilinear）+ strip 銳化（預設開）。
                stripPad = prefs.stripPad.get().coerceIn(0, 12),
                useBicubic = prefs.useBicubic.get() == "bicubic",
                ocrUnsharp = prefs.ocrUnsharp.get(),
                concurrent = true,
                concurrency = ocrConcurrency,
            ),
            translator = translatorCfg,
            inpainter = InpainterConfig(
                method = method,
                bboxPad = pi(prefs.bboxPad.get(), 0, 64, 16),
                // 進階去字：整頁去字解析度（三檔 512/768/1024，clamp 保險）+ 遮罩膨脹（8–40，存 Int→Float）。
                tileSize = prefs.tileSize.get().coerceIn(512, 1024),
                maskDilate = prefs.maskDilate.get().coerceIn(8, 40).toFloat(),
            ),
            render = RenderConfig(
                orientation = orient,
                fontBorder = prefs.fontBorder.get(),
                colorMode = if (prefs.colorMode.get() == "mono") "mono" else "auto",
                artStrokeRatio = pf(prefs.artStrokeRatio.get(), 0f, 0.5f, 0.16f),
                fontSizeMax = pi(prefs.fontSizeMax.get(), 20, 120, 60),
                fontSizeMin = pi(prefs.fontSizeMin.get(), 6, 40, 9),
                colTrim = pi(prefs.colTrim.get(), 0, 10, 3),
                rowTrim = pi(prefs.rowTrim.get(), 0, 10, 3),
                fontScale = pf(prefs.fontScale.get(), 0.3f, 1.5f, 0.85f),
                // 進階排版：縱中橫（直排短 ASCII 串水平並排，預設開）。
                tateChuYoko = prefs.tateChuYoko.get(),
            ),
        )
    }

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
