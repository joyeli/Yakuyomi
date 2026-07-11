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
     * 模型三顆是否齊（detector / ocr / 去字）。給 [PageTranslator.isReady] / [TranslationEngineService.isReady] 共用。
     * 去字：AOT（v2 主，NCNN `.param` 或 ORT `.onnx`）或 LaMa（退役備援）任一即算。自動下載區 + SAF BYOM 區擇一即可。
     */
    fun hasAllModels(context: Context): Boolean {
        val saf = modelsDir(context)
        return rolePresent(context, saf, "detect", "comictext") &&
            rolePresent(context, saf, "ocr") &&
            (rolePresent(context, saf, "aot") || rolePresent(context, saf, "lama"))
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
            context.stringResource(MR.strings.model_role_detect) to rolePresent(context, saf, "detect", "comictext"),
            context.stringResource(MR.strings.model_role_ocr) to rolePresent(context, saf, "ocr"),
            context.stringResource(MR.strings.model_role_inpaint) to (rolePresent(context, saf, "aot") || rolePresent(context, saf, "lama")),
        )
    }

    /**
     * 模型「齊但過時」＝v1 舊模型（ORT 偵測 + LaMa `.onnx`）還在、但缺 v2 的 NCNN 交付（偵測/去字 `.param`）。
     *
     * 為何重要：升級 app（引擎 v2）後若沿用舊模型，[modelPresence] 仍全 ✓（關鍵字 + `.onnx` fallback 命中舊檔），
     * 但引擎會拿 LaMa 當 `auto_aot` 去字模型跑（I/O 契約不符）→ **去字輸出壞掉**，使用者卻不知要換。
     * 用這個旗標在設定頁把「模型齊全」改成「舊版·請重新下載」，並把下載鈕標成「更新模型」。
     *
     * 判準＝偵測與去字**都**要有 NCNN `.param`（v2 唯一交付）。舊 v1 兩者皆無 → 過時。
     * BYOM 進階者刻意用 ONNX 也會被提示更新（可接受：v2 主推 NCNN，重下載即取得正確集）。
     */
    fun modelsOutdated(context: Context): Boolean {
        if (!hasAllModels(context)) return false // 缺模型自有「missing」提示、不重複
        val saf = modelsDir(context)
        val detectorNcnn = presentExt(context, saf, ".param", "detect", "comictext")
        val aotNcnn = presentExt(context, saf, ".param", "aot")
        return !(detectorNcnn && aotNcnn)
    }

    /**
     * 解析三顆模型 → 本機路徑 [ModelSet] + 載入 OCR 字元表。缺任一顆模型回 null（呼叫端應略過翻譯）。
     *
     * SAF 模型先串流複製到 filesDir（[ensureLocal]，off-heap 路徑載入，避開 512MB JVM heap OOM；§10）。
     * 複製在背景執行緒（呼叫端的 suspend translate 內）發生、不卡 UI。
     */
    fun resolveModelSet(context: Context): ModelSetBundle? {
        val saf = modelsDir(context)
        val ocr = resolveOnnxRole(context, saf, "ocr") ?: return null
        // 偵測：NCNN `.param` 優先、ORT `.onnx` 備援（v2 交付只有 NCNN；BYOM 可放任一）。
        val detNcnn = resolveNcnnRole(context, saf, "detect", "comictext")
        val detOnnx = resolveOnnxRole(context, saf, "detect", "comictext")
        if (detNcnn == null && detOnnx == null) return null
        // 去字：NCNN AOT 優先、ORT AOT / LaMa（退役）備援。
        val aotNcnn = resolveNcnnRole(context, saf, "aot")
        val aotOnnx = resolveOnnxRole(context, saf, "aot")
        val lama = resolveOnnxRole(context, saf, "lama")
        if (aotNcnn == null && aotOnnx == null && lama == null) return null
        val alphabet = context.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
        return ModelSetBundle(
            ModelSet(
                detector = detOnnx,
                ocr = ocr,
                inpainter = lama,
                aotInpainter = aotOnnx,
                detectorNcnn = detNcnn,
                aotInpainterNcnn = aotNcnn,
            ),
            alphabet,
        )
    }

    /**
     * 解析去字模型路徑（給重繪等「只需去字」的路徑用）：NCNN AOT `.param` 優先（同時確保 `.bin` 在本機）、
     * ORT AOT `.onnx` / LaMa（退役）備援。缺回 null。[Inpainter] 依副檔名分流（`.param`→NCNN）。
     */
    fun resolveInpaintModel(context: Context): String? {
        val saf = modelsDir(context)
        return resolveNcnnRole(context, saf, "aot")
            ?: resolveOnnxRole(context, saf, "aot")
            ?: resolveOnnxRole(context, saf, "lama")
    }

    /**
     * 去字方法字串（[TranslationPreferences.inpaintMethod] 原始值）→ 引擎 (method, wholeImage)。v2 兩門別：
     *   boxfill＝快速去字（全平塗就近取色·快·壓畫面塗色塊）／其餘＝AI 去字（純 AOT-GAN 整頁 768·全區重建·預設）。
     * ★ 一律純 aot（無 auto 逐區路由）：舊的 auto_aot 會在乾淨泡泡走平塗、AI 去字看起來有些地方是 boxfill──已改純 aot、全區都 AOT。
     * 舊存的 auto_whole/auto_tile/auto_aot/lama_*（auto 路由 + LaMa/逐格已退役）都落 else＝AI 去字（引擎把 auto* 一律當 aot）。
     *
     * 即時翻譯一律傳 "boxfill"（低延遲）；離線整章翻傳使用者的 inpaintMethod。集中於此一處映射、兩路共用。
     */
    fun mapInpaintMethod(methodRaw: String): Pair<String, Boolean> = when (methodRaw) {
        "boxfill" -> "boxfill" to true
        else -> "aot" to true // AI 去字＝純 AOT-GAN 整頁 768（全區重建、無 auto 路由）；舊 auto_*/lama_* 都落這
    }

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
     * @param methodRaw 去字方法原始字串（[TranslationPreferences.inpaintMethod] 值，或即時翻譯固定的 "boxfill"）。
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
        var translatorCfg = TranslatorConfig(
            provider = preset.id,
            model = prefs.model.get().ifBlank { preset.defaultModel },
            // base 空（自架/自訂未填）→ isReady 已擋；萬一漏 → LlmTranslator 拋例外標 Failed（不靜默）。
            apiBase = chatUrl,
            toLangName = target,
            fromLangName = prefs.sourceLangName.get(),
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

        // 去字方法（3 階梯）
        val (method, whole) = mapInpaintMethod(methodRaw)

        // 緒數（裝置相依）
        val cores = Runtime.getRuntime().availableProcessors()
        // OCR 逐行並發度：auto=核數 / 2/4/6/8（concurrent 鎖 true）。真機 8.9s→4.8s。
        val ocrConcurrency = when (val v = prefs.ocrConcurrency.get()) {
            "auto" -> cores
            else -> (v.toIntOrNull() ?: cores).coerceIn(1, 32)
        }
        // 推論執行緒（偵測+去字 lama）：auto=大核數估算(cores-2，big.LITTLE 留 2 小核) / 2/4/6/8。真機 6 最快。
        val intra = when (val v = prefs.intraThreads.get()) {
            "auto" -> (cores - 2).coerceAtLeast(2)
            else -> (v.toIntOrNull() ?: 6).coerceIn(1, 32)
        }

        // 進階數值：存字串、此處 parse + clamp 到值域（超界夾回，不擋存）。
        fun pf(s: String, lo: Float, hi: Float, d: Float) = s.toFloatOrNull()?.coerceIn(lo, hi) ?: d
        fun pi(s: String, lo: Int, hi: Int, d: Int) = s.toIntOrNull()?.coerceIn(lo, hi) ?: d

        return EngineConfig(
            detector = DetectorConfig(
                segThreshold = pf(prefs.segThreshold.get(), 0f, 1f, 0.12f),
                intraThreads = intra,
            ),
            ocr = OcrConfig(
                minProb = pf(prefs.minProb.get(), 0f, 1f, 0.5f),
                concurrent = true,
                concurrency = ocrConcurrency,
            ),
            translator = translatorCfg,
            inpainter = InpainterConfig(
                method = method,
                wholeImage = whole,
                autoStdThreshold = pf(prefs.autoStdThreshold.get(), 0f, 30f, 6f),
                autoWhiteThreshold = pf(prefs.autoWhiteThreshold.get(), 0f, 255f, 190f),
                bboxPad = pi(prefs.bboxPad.get(), 0, 64, 16),
                intraThreads = intra,
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
