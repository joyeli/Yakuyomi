package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 翻譯引擎的**常駐（warm）服務**（process singleton，於 [AppModule] 註冊）。
 *
 * 用途：讓整章翻（[PageTranslator.translateChapter] 逐章透過本服務）共用**同一顆**引擎實例，
 * 避免佇列一章接一章 drain 時、每章 `Yakuyomi.create(...).use { }` 都重載 ~100MB native + 重編譯 ORT 圖
 * （M4 ⑦ 引擎生命週期）。即時翻譯開著時尤其關鍵：reader 連讀多章＝佇列連翻多章。
 *
 * **去字法可變**：[translatePage] 帶 `methodRaw` 參數（boxfill / auto_whole / auto_tile）。
 * 同一個去字法連續呼叫＝復用 warm 引擎；去字法在章與章間變了＝簽章變→重建（見 [ensureEngine]/[configSignature]）。
 * （即時翻走自己的 liveInpaintMethod pref、不是固定值；本服務服務的是受管理佇列的整章翻。）
 *
 * **並發（跨頁流水線）**：warm 引擎的 [translatePage] **可並發呼叫**（偵測/OCR/翻譯/去字 session 共用、真機實測
 * 併發翻多頁不 crash、不汙染輸出）→ reader/佇列可把「頁 N 的網路翻譯」疊上「頁 N+1 的裝置端偵測/OCR」，
 * 淺併發（~4）達約 2× 循序速率（見 [PageTranslator] 的 pipelineDepth）。[mutex] 只序列化**引擎生命週期**
 * （建/重建/關），不再序列化每頁推論；在飛頁數由 [inFlight] 計數，關閉前等它歸零（見 [shutdown]/[shutdownBlocking]）。
 * **生命週期**：lazy 建（首次 [translatePage] 或 [warmUp] 才建、不拖 app 冷啟）；設定改了（簽章變）下次呼叫重建；
 *   **不在翻完一頁/一章後關**（這就是常駐的意義）。釋放由三個外部觸發負責（見 [shutdown]/[shutdownBlocking]）：
 *   即時翻關 / 真記憶體壓力（onTrimMemory）/ 即時翻關時佇列清空。
 *
 * **記憶體**：warm 引擎在常駐期間持有 ~100MB native；上述三觸發任一發生即釋放，下次呼叫再 lazy 重建。
 */
class TranslationEngineService(private val context: Context) {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    /**
     * 只序列化引擎**生命週期**（建/重建/關）＋在飛計數的註冊點——**不**序列化每頁推論（跨頁流水線靠此）。
     * 每頁翻譯只在此鎖下短暫「確保引擎已建 + [inFlight]++」，隨即放鎖去跑 `engine.translatePage`（可多頁並發）。
     */
    private val mutex = Mutex()

    /** 目前在引擎內飛的頁數（跨頁併發下 >1）。關閉前等它歸零，避免關掉正在用的 native session（use-after-close）。 */
    private val inFlight = AtomicInteger(0)

    /**
     * 目前這個 build 是否已完成過**至少一次**推論（各原生 session 的首次 lazy 初始化都在單緒跑過了）。
     * false＝冷引擎（剛建好、還沒推論過）→ 第一頁**持鎖單緒**跑完再放行併發，避免多頁同時打進未初始化的
     * NCNN/ORT session → 原生 SIGSEGV（「開 app 後第一本翻譯就閃退」的真凶）。重建（[closeEngine]）會重置成 false。
     * 只在 [mutex] 下讀寫。
     */
    private var warmedUp = false

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
        return TranslationEngineConfig.modelsResolvable(context)
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
     * **並發**：只在 [mutex] 下短暫「確保引擎已建（模型 SAF→filesDir 複製也在此）＋ [inFlight]++」，
     * 隨即放鎖去跑 `engine.translatePage`——所以多頁可同時在引擎內（跨頁流水線）。關閉會等 [inFlight] 歸零。
     *
     * @param methodRaw 去字法原始字串（boxfill / auto_whole / auto_tile）；與 warm 引擎當前去字法不同 → 重建引擎。
     *   同一章逐頁去字法相同 → 首頁後 [ensureEngine] 是 no-op、不重建（重建只在章邊界、此時 [inFlight]=0）。
     */
    suspend fun translatePage(src: Bitmap, methodRaw: String): PageResult {
        // 在鎖下：確保引擎已建、註冊在飛頁，並判定「這是不是這個 build 的第一次推論」（[warmedUp]，鎖下讀無 TOCTOU）。
        // 冷引擎的第一頁 → **持鎖單緒**跑完（讓 NCNN/ORT 各 session 首次 lazy 初始化在單緒完成），之後才放行併發。
        // 修「開 app 後第一本翻譯（下載/即時皆是），多頁同時打進剛載好、還沒推論過的原生 session → SIGSEGV 閃退」。
        TraceLog.log(
            "svc",
            "translatePage.enter ${src.width}x${src.height} method=$methodRaw warm=$warmedUp if=${inFlight.get()}",
        )
        var engineForConcurrent: TranslationEngine? = null
        val handled: PageResult? = mutex.withLock {
            val e = ensureEngine(methodRaw)
                ?: return@withLock PageResult.Failed(context.stringResource(MR.strings.engine_unavailable))
            inFlight.incrementAndGet() // 在鎖下註冊 → 關閉不會在註冊中途插入而漏算在飛頁
            if (!warmedUp) {
                // 冷引擎首次推論：整段持鎖跑（其他頁在 mutex.withLock 上等），暖完設 warmedUp=true 才放行併發。
                // 代價＝只有冷啟動後第一本的第一頁不被跨頁重疊（含一次網路往返）；換得不再撞冷 session。
                try {
                    e.translatePage(src).also { warmedUp = true }
                } catch (ex: Throwable) {
                    logcat(LogPriority.ERROR, ex) { "warm 引擎翻譯單頁失敗（冷引擎首次）" }
                    PageResult.Failed(ex.message ?: context.stringResource(MR.strings.translate_exception))
                } finally {
                    inFlight.decrementAndGet()
                }
            } else {
                engineForConcurrent = e // 已暖 → 放鎖後併發跑
                null
            }
        }
        if (handled != null) return handled
        // 已暖 → 放鎖、多頁並發進引擎（跨頁流水線）。
        val engine = engineForConcurrent!!
        TraceLog.log("svc", "translatePage.concurrent.call inFlight=${inFlight.get()}")
        return try {
            engine.translatePage(src)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "warm 引擎翻譯單頁失敗" }
            PageResult.Failed(e.message ?: context.stringResource(MR.strings.translate_exception))
        } finally {
            inFlight.decrementAndGet()
            TraceLog.log("svc", "translatePage.concurrent.done inFlight=${inFlight.get()}")
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
     * 取常駐引擎；簽章（含去字法）變了先關舊再重建，沒建過就建。建不起來（模型缺/例外）回 null。
     * **僅在 [mutex] 下呼叫**（[translatePage]/[warmUp] 內）。
     *
     * **併發安全（關鍵）**：重建要 [closeEngine]（釋放 native session）——但**絕不可**在有頁在飛（[inFlight]>0）時關，
     * 否則正在跑推論的頁 use-after-close 會 native crash。兩個獨立消費者（佇列 drain + reader「翻譯這頁」）去字法不同時
     * 會撞到這裡：**有現成 warm 引擎且有頁在飛 → 先用現有引擎服務本頁**（去字法可能與請求略不同、但不崩潰），
     * 延後重建到 idle；只有 inFlight==0 才安全關舊重建。本呼叫者此刻尚未 inFlight++（在鎖下、緊接 ensureEngine 之後才 ++），
     * 故此處讀到的 inFlight 反映的是**其他**在飛頁；持鎖期間沒有新頁能註冊，故 inFlight==0 判定後關閉是安全的。
     */
    private fun ensureEngine(methodRaw: String): TranslationEngine? {
        val signature = configSignature(methodRaw)
        val current = engine
        if (current != null && signature == builtSignature) return current

        // 需重建（簽章變）或首建。有現成引擎但別的頁在飛 → 不能關（use-after-close）→ 先用現有引擎服務本頁、延後重建。
        if (current != null && inFlight.get() > 0) {
            logcat(LogPriority.DEBUG) { "延後重建引擎：有 ${inFlight.get()} 頁在飛，本頁先用現有 warm 引擎" }
            return current
        }
        // inFlight==0（或還沒建過）→ 安全關舊重建（釋放舊 native session 才不疊加 ~100MB）。
        TraceLog.log("svc", "ensureEngine.rebuild sig=$signature hadEngine=${current != null}")
        closeEngine()

        // 真正建構（載入 ~100MB）期間 → loading=true，給 reader 指示器顯示「引擎載入中…」。finally 確保任何出口都歸位。
        _loading.value = true
        return try {
            // 模型解析 + 字元表（SAF→filesDir 複製在此；缺模型回 null）。與離線翻共用同一份解析。
            TraceLog.log("svc", "ensureEngine.resolveModels")
            val bundle = TranslationEngineConfig.resolveModelSet(context) ?: return null
            // 去字法照呼叫端傳入（佇列逐章帶來的去字法），其餘參數（語言/緒數/排版…）照使用者設定。
            val cfg = TranslationEngineConfig.buildEngineConfig(translationPreferences, methodRaw)
            TraceLog.log("svc", "ensureEngine.create.start")
            val built = Yakuyomi.create(bundle.models, bundle.alphabet, apiKey(), cfg)
            TraceLog.log("svc", "ensureEngine.create.done")
            engine = built
            builtSignature = signature
            _warm.value = true
            // 建構後**單緒**暖各原生 session（detector/OCR/去字 各空跑一次推論）→ 首次真推論不再撞冷 session。
            // 成功 → warmedUp=true：第一頁即可全併發、零損失。失敗 → 留 false，退回 translatePage 的第一頁序列化保險。
            runCatching { built.warmUp() }
                .onSuccess { warmedUp = true }
                .onFailure { logcat(LogPriority.WARN, it) { "引擎暖機失敗，退回第一頁序列化保險" } }
            built
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
     *
     * **維護鐵則：本清單必須涵蓋 [TranslationEngineConfig.buildEngineConfig] 讀到的每一個 pref**
     * （＋ [apiKey]，它不在 buildEngineConfig 裡、是另外傳給 `Yakuyomi.create`）。
     * 漏一個＝改了那個設定卻沿用 warm 引擎、舊值繼續生效（曾漏 provider/model/apiBase/temperature →
     * 使用者在設定換 model 後請求仍打舊 model、持續 HTTP 400；改 API key 反而生效，只因 key 有在簽章裡）。
     * 日後在 buildEngineConfig 加讀任何 pref，**同時**在此加一行；順序刻意對齊 buildEngineConfig 的分區。
     */
    private fun configSignature(methodRaw: String): String {
        val p = translationPreferences
        return listOf(
            methodRaw, // 去字法納入簽章：換去字法 → 重建引擎
            // —— LLM（TranslatorConfig）：換 provider/model/apiBase/temperature 都要重建才會套用 ——
            apiKey(), // 不經 buildEngineConfig，另外直接餵 Yakuyomi.create
            p.provider.get(),
            p.model.get(),
            p.apiBase.get(),
            p.temperature.get(),
            p.thinking.get().toString(), // 思考模式（per-provider 參數映射）→ 換了要重建才會套用
            p.targetLangName.get(), // 也決定要不要清掉引擎內建 few-shot
            p.sourceLangName.get(),
            // —— 偵測（DetectorConfig）——
            p.segThreshold.get(),
            p.detectUnsharp.get().toString(),
            p.dbnetSize.get().toString(),
            // —— OCR（OcrConfig）——
            p.minProb.get(),
            p.ignoreSfx.get().toString(),
            p.stripPad.get().toString(),
            p.useBicubic.get(),
            p.ocrUnsharp.get().toString(),
            p.ocrConcurrency.get(),
            // —— 去字（InpainterConfig）——
            p.bboxPad.get(),
            p.tileSize.get().toString(),
            p.maskDilate.get().toString(),
            // —— 排版（RenderConfig）——
            p.orientation.get(),
            p.fontBorder.get().toString(),
            p.colorMode.get(),
            p.artStrokeRatio.get(),
            p.fontSizeMax.get(),
            p.fontSizeMin.get(),
            p.colTrim.get(),
            p.rowTrim.get(),
            p.fontScale.get(),
            p.tateChuYoko.get().toString(),
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
        warmedUp = false // 新引擎的 session 又是冷的 → 下次首頁重新單緒暖過再放行併發
    }

    /**
     * 對外關閉（即時翻關 / 即時翻關時佇列清空）：在 [mutex] 下釋放 warm 引擎。下次 [translatePage]/[warmUp] 會 lazy 重建。
     */
    suspend fun shutdown() = mutex.withLock {
        // 持鎖期間沒有新頁能註冊（translatePage 的 inFlight++ 也要此鎖）；等已在飛的頁翻完再關（跨頁併發下可能多頁在飛）。
        // delay 不釋放 Mutex → 新翻譯續阻塞、在飛頁自然遞減，歸零即關。上限 ~30s 保險：避免某頁卡死永不歸零。
        var spins = 0
        while (inFlight.get() > 0 && spins++ < 1500) delay(20)
        // ★ 只有 inFlight==0 才關——即使上限到了仍有頁在飛也**不**關（否則 native session 被關掉、在飛頁 use-after-close crash）。
        if (inFlight.get() == 0) {
            closeEngine()
        } else {
            logcat(LogPriority.WARN) { "shutdown 略過關閉：仍有 ${inFlight.get()} 頁在飛（避免 use-after-close，引擎續 warm）" }
        }
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
                // 有頁在飛就別關（非 suspend、不能等）——留給下次觸發或翻完後再關，避免 use-after-close。
                if (inFlight.get() == 0) {
                    closeEngine()
                } else {
                    logcat(LogPriority.DEBUG) { "shutdownBlocking 略過：有頁在飛（記憶體回收稍後重試）" }
                }
            } finally {
                mutex.unlock()
            }
        } else {
            logcat(LogPriority.DEBUG) { "shutdownBlocking 略過：引擎生命週期鎖忙（記憶體回收稍後重試）" }
        }
    }
}
