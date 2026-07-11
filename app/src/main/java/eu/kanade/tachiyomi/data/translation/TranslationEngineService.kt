package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Yakuyomi
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 翻譯引擎的**常駐（warm）服務**（process singleton，於 [AppModule] 註冊）。
 *
 * 用途：讓整章翻（[PageTranslator.translateChapter] 逐章透過本服務）共用**同一顆**引擎實例，
 * 避免佇列一章接一章 drain 時、每章 `Yakuyomi.create(...).use { }` 都重載 ~100MB native + 重編譯 ORT 圖
 * （M4 ⑦ 引擎生命週期）。即時翻譯開著時尤其關鍵：reader 連讀多章＝佇列連翻多章。
 *
 * **去字法可變**：[translatePage] 帶 `methodRaw` 參數（boxfill / auto_whole / auto_tile）。
 * 同一個去字法連續呼叫＝復用 warm 引擎；去字法在章與章間變了＝簽章變→重建（見 [ensureEngine]/[configSignature]）。
 * （即時逐頁低延遲的「固定 boxfill」策略不在本服務——本服務服務的是受管理佇列的整章翻。）
 *
 * **並發**：單一引擎實例**非並發安全**（同實例同時翻多頁會壞）→ 用 [mutex] 序列化所有引擎存取
 * （[translatePage]/[warmUp]/[shutdown]/重建）。佇列 drain 本就是單一消費者（drainMutex），此鎖為額外保險。
 * **生命週期**：lazy 建（首次 [translatePage] 或 [warmUp] 才建、不拖 app 冷啟）；設定改了（簽章變）下次呼叫重建；
 *   **不在翻完一頁/一章後關**（這就是常駐的意義）。釋放由三個外部觸發負責（見 [shutdown]/[shutdownBlocking]）：
 *   即時翻關 / 真記憶體壓力（onTrimMemory）/ 即時翻關時佇列清空。
 *
 * **記憶體**：warm 引擎在常駐期間持有 ~100MB native；上述三觸發任一發生即釋放，下次呼叫再 lazy 重建。
 */
class TranslationEngineService(private val context: Context) {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    /** 引擎非並發安全 → 所有引擎存取（翻譯/暖機/關閉/重建）都在此鎖下序列化（一次一頁進引擎）。 */
    private val mutex = Mutex()

    /** 暖機/關閉的背景 scope（IO）：給 UI（即時翻開關）fire-and-forget，**絕不**在主執行緒上跑 ~100MB 載入/關閉或等鎖（會 ANR）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 常駐引擎（lazy 建、跨頁/章復用）。null＝還沒建或已 [shutdown]。只在 [mutex] 下讀寫。 */
    private var engine: TranslationEngine? = null

    /** 上次建引擎時的設定簽章（含去字法）；與當前簽章不同 → 設定/去字法改過 → 重建。只在 [mutex] 下讀寫。 */
    private var builtSignature: String? = null

    /** 引擎是否正在建構（載入 ~100MB native）：給 reader 角落指示器顯示「引擎載入中…」。建構期間 true、完成/失敗轉 false。 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 引擎是否已預載（warm，建好常駐中）：給書庫工具列圖示顯示「有/無預載」。建好＝true、釋放＝false。 */
    private val _warm = MutableStateFlow(false)
    val warm: StateFlow<Boolean> = _warm.asStateFlow()

    /** key：優先設定頁（BYOK）；空白時 fallback build-time key（與 [PageTranslator] 同規則）。 */
    private fun apiKey(): String =
        translationPreferences.activeApiKey().ifBlank {
            // baked key 只是 DeepSeek 的冒煙測試後備；換 provider 後不套用（免拿 DeepSeek key 去打別家）。
            if (translationPreferences.provider.get() == "deepseek") BuildConfig.DEEPSEEK_API_KEY else ""
        }

    /**
     * 引擎是否就緒：key 有設 + 3 顆模型齊。給 [ChapterLoader] 決定要不要包 [TranslatingPageLoader]。
     *
     * **刻意不檢查 [TranslationPreferences.translationEnabled]**——那是「下載時翻譯章節」（離線整章翻）的開關，
     * 手動翻的使用者常關著它；即時翻譯由 [TranslationPreferences.liveTranslate] 獨立控制（在 [ChapterLoader.shouldTranslateLive] 檢查），
     * 故此處只看 key + 模型，否則即時翻會被 translationEnabled 靜默擋掉。
     */
    fun isReady(): Boolean {
        if (apiKey().isBlank()) return false
        if (TranslationEngineConfig.isProviderBaseMissing(translationPreferences)) return false
        return TranslationEngineConfig.hasAllModels(context)
    }

    /**
     * 用 warm 引擎翻譯單頁，回**原始 [PageResult]**（Translated/Skipped/Failed 不收斂成 null——
     * 呼叫端 [PageTranslator.translateChapter] 要靠這三態做 §11：成功覆蓋+記 manifest／略過記 manifest／失敗留原圖可重試）。
     *
     * 不 recycle 輸入 [src]（所有權屬呼叫端）；成功時 [PageResult.Translated.page] 是引擎產出的新 bitmap。
     *
     * 引擎建不起來（缺模型/缺 key/建構例外）→ 回 [PageResult.Failed]（不丟例外）：呼叫端把它當「該頁失敗、留原圖」處理，
     * 不會誤把原圖蓋掉、也不會中斷整章迴圈（§11）。
     *
     * 在 [mutex] 下序列化：一次只有一頁進引擎；模型 SAF→filesDir 複製（[TranslationEngineConfig.resolveModelSet]）
     * 也在此（suspend、背景 dispatcher）發生、不卡 UI。
     *
     * @param methodRaw 去字法原始字串（boxfill / auto_whole / auto_tile）；與 warm 引擎當前去字法不同 → 重建引擎。
     */
    suspend fun translatePage(src: Bitmap, methodRaw: String): PageResult = mutex.withLock {
        val engine = ensureEngine(methodRaw)
            ?: return@withLock PageResult.Failed(context.stringResource(MR.strings.engine_unavailable))
        try {
            engine.translatePage(src)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "warm 引擎翻譯單頁失敗" }
            PageResult.Failed(e.message ?: context.stringResource(MR.strings.translate_exception))
        }
    }

    /**
     * 預暖機：先把引擎建好（不翻任何頁），讓第一章/第一頁瞬間就緒。best-effort，建不起來只記 log、不丟例外。
     * 由即時翻譯開關打開時呼叫（見 [SettingsTranslationScreen]）；不呼叫也無妨——首次 [translatePage] 會 lazy 建。
     *
     * @param methodRaw 預暖用的去字法（預設＝目前全域去字偏好）；之後 [translatePage] 帶不同去字法仍會按簽章重建。
     */
    suspend fun warmUp(methodRaw: String = translationPreferences.inpaintMethod.get()) = mutex.withLock {
        ensureEngine(methodRaw)
        Unit
    }

    /**
     * 取常駐引擎；簽章（含去字法）變了先 [closeEngine] 再重建，沒建過就建。建不起來（模型缺/例外）回 null。
     * **僅在 [mutex] 下呼叫**（[translatePage]/[warmUp] 內）。**不**在這裡關引擎——關只發生在三個外部觸發。
     */
    private fun ensureEngine(methodRaw: String): TranslationEngine? {
        val signature = configSignature(methodRaw)
        if (engine != null && signature == builtSignature) return engine

        // 設定/去字法改過 → 丟舊引擎重建（釋放舊 native session 才不疊加 ~100MB）。
        closeEngine()

        // 真正建構（載入 ~100MB）期間 → loading=true，給 reader 指示器顯示「引擎載入中…」。finally 確保任何出口都歸位。
        _loading.value = true
        return try {
            // 模型解析 + 字元表（SAF→filesDir 複製在此；缺模型回 null）。與離線翻共用同一份解析。
            val bundle = TranslationEngineConfig.resolveModelSet(context) ?: return null
            // 去字法照呼叫端傳入（佇列逐章帶來的去字法），其餘參數（語言/緒數/排版…）照使用者設定。
            val cfg = TranslationEngineConfig.buildEngineConfig(translationPreferences, methodRaw)
            engine = Yakuyomi.create(bundle.models, bundle.alphabet, apiKey(), cfg)
            builtSignature = signature
            _warm.value = true
            engine
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "建翻譯引擎失敗" }
            closeEngine()
            null
        } finally {
            _loading.value = false
        }
    }

    /**
     * 影響引擎建構的設定 + 去字法的簽章。值變了＝要重建引擎（設定/去字法即時生效）。
     * 去字法（[methodRaw]）納入 → 章與章間換去字法會重建；其餘語言/緒數/OCR/排版等改了也重建。
     */
    private fun configSignature(methodRaw: String): String {
        val p = translationPreferences
        return listOf(
            methodRaw, // 去字法納入簽章：換去字法 → 重建引擎
            apiKey(),
            p.targetLangName.get(),
            p.sourceLangName.get(),
            p.orientation.get(),
            p.ocrConcurrency.get(),
            p.intraThreads.get(),
            p.colorMode.get(),
            p.fontBorder.get().toString(),
            p.segThreshold.get(),
            p.minProb.get(),
            p.autoStdThreshold.get(),
            p.autoWhiteThreshold.get(),
            p.bboxPad.get(),
            p.artStrokeRatio.get(),
            p.fontSizeMax.get(),
            p.fontSizeMin.get(),
            p.colTrim.get(),
            p.rowTrim.get(),
            p.fontScale.get(),
        ).joinToString("\u0000") // 以 NUL 分隔避免相鄰欄位串接後碰撞（語言名/key 可能含空白）
    }

    /** 釋放引擎的原生資源（3 顆 ONNX session）。僅在 [mutex] 下呼叫。 */
    private fun closeEngine() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "關閉翻譯引擎時例外" }
        }
        engine = null
        builtSignature = null
        _warm.value = false
    }

    /**
     * 對外關閉（即時翻關 / 即時翻關時佇列清空）：在 [mutex] 下釋放 warm 引擎。下次 [translatePage]/[warmUp] 會 lazy 重建。
     */
    suspend fun shutdown() = mutex.withLock {
        closeEngine()
    }

    /** [warmUp] 的 fire-and-forget 版（背景 IO、不阻塞呼叫端）：給 UI 即時翻開關用，避免在主執行緒上載 ~100MB → ANR/crash。 */
    fun warmUpAsync() {
        scope.launch { runCatching { warmUp() } }
    }

    /** [shutdown] 的 fire-and-forget 版（背景 IO、不阻塞呼叫端）：給 UI 即時翻開關用，避免在主執行緒上等鎖/關閉 → ANR/crash。 */
    fun shutdownAsync() {
        scope.launch { runCatching { shutdown() } }
    }

    /**
     * 非 suspend 關閉（給 [App.onTrimMemory] 等不能 suspend 的 callback 用）：
     * 用 `tryLock` 嘗試拿鎖後關。**拿不到鎖（正在翻某頁）就略過本次**——不阻塞系統的記憶體回收 callback，
     * 也不打斷正在飛的單頁推論；下次真有壓力時系統會再叫一次，或翻完該頁後下個觸發再關。
     */
    fun shutdownBlocking() {
        if (mutex.tryLock()) {
            try {
                closeEngine()
            } finally {
                mutex.unlock()
            }
        } else {
            logcat(LogPriority.DEBUG) { "shutdownBlocking 略過：引擎正在使用中（記憶體回收稍後重試）" }
        }
    }
}
