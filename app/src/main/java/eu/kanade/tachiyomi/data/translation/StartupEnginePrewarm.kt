package eu.kanade.tachiyomi.data.translation

import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.crash.importanceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 開 app 時預暖翻譯引擎（即時翻譯開著時，先把引擎載好，讀第一頁就不用等）——**只在使用者真的把 app 開到前景之後**。
 *
 * 以前在 App.onCreate 直接預暖，背景工作（WorkManager、廣播、前景服務）把行程叫起來時也照做：真機紀錄一次吃掉約 15 秒
 * CPU 與 1.3–2 GB 原生記憶體，使用者根本沒在看（2026-10-06 使用者拍板改掉）。流程見 [StartupPrewarmController]；
 * 這個物件只把 Android 的東西（偏好、引擎服務、行程生命週期、行程重要度、診斷紀錄）接上去。
 *
 * 另外記 [everForeground]：這個行程到過前景沒有。翻譯佇列做完時，從沒到過前景的行程（背景工作叫起來的）不留引擎
 * （見 TranslationManager 的 drain 收尾）。
 *
 * 入口都在主執行緒呼叫（Application／生命週期 callback／MainActivity）。
 */
object StartupEnginePrewarm {

    private const val TAG = "startup"

    private val controller = StartupPrewarmController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        io = Dispatchers.IO,
        env = AndroidPrewarmEnv,
    )

    /** 這個行程到過前景沒有（任何執行緒都可以讀）。 */
    val everForeground: Boolean get() = controller.everForeground

    /** App.onCreate：只判斷、記紀錄，**不**預暖。 */
    @MainThread
    fun onProcessStart(masterEnabled: Boolean, liveTranslate: Boolean) {
        controller.onProcessStart(masterEnabled, liveTranslate)
    }

    /** ProcessLifecycleOwner 的 ON_START（使用者把 app 帶到前景）。 */
    @MainThread
    fun onForeground() {
        controller.onForeground()
    }

    /** ProcessLifecycleOwner 的 ON_STOP。 */
    @MainThread
    fun onBackground() {
        controller.onBackground()
    }

    /** MainActivity.ready 變 true（第一個分頁內容出來）。 */
    @MainThread
    fun onFirstScreenReady() {
        controller.onFirstScreenReady()
    }

    /** MainActivity 被銷毀（之後再開是新的 Activity，要等它自己的第一個畫面）。 */
    @MainThread
    fun onScreenGone() {
        controller.onScreenGone()
    }

    private object AndroidPrewarmEnv : PrewarmEnv {
        private val prefs by lazy { Injekt.get<TranslationPreferences>() }
        private val engine by lazy { Injekt.get<TranslationEngineService>() }

        override fun masterEnabled() = prefs.translationMasterEnabled.get()
        override fun liveTranslate() = prefs.liveTranslate.get()
        override fun engineWarm() = engine.warm.value
        override fun engineLoading() = engine.loading.value
        override fun engineReady() = engine.isReady()
        override fun stillForeground() =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        // 預暖就是為了即時翻譯：用即時翻譯的去字法建（見 TranslationEngineService.warmUpAsync）
        override fun startWarmUp() = engine.warmUpAsync(forLive = true)

        override fun importance(): Int? = runCatching {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        }.getOrNull()

        override fun sinceProcessStartMs(): Long = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

        override fun log(line: String) {
            TraceLog.log(TAG, line)
            logcat(LogPriority.DEBUG) { line }
        }

        override fun logError(e: Throwable) {
            logcat(LogPriority.WARN, e) { "啟動預暖判斷失敗" }
        }
    }
}

/** [StartupPrewarmController] 要的外部狀態與動作（正式版接 Android；單元測試換假的）。 */
internal interface PrewarmEnv {
    fun masterEnabled(): Boolean
    fun liveTranslate(): Boolean
    fun engineWarm(): Boolean
    fun engineLoading(): Boolean

    /** 金鑰與模型齊不齊：會碰檔案系統，只在 io 上呼叫。 */
    fun engineReady(): Boolean

    /** 當下 app 在不在前景（判斷當下再確認一次）。 */
    fun stillForeground(): Boolean

    /** 開始預暖（fire-and-forget）。 */
    fun startWarmUp()

    /** 行程重要度（RunningAppProcessInfo.importance；查不到＝null）：一次 Binder 呼叫，只在 io 上呼叫。 */
    fun importance(): Int?

    fun sinceProcessStartMs(): Long

    /** 寫診斷紀錄（TraceLog 啟用時每行都整檔重寫）：只在 io 上呼叫。 */
    fun log(line: String)

    fun logError(e: Throwable)
}

/**
 * 啟動預暖的流程（純 Kotlin、協程；[StartupEnginePrewarm] 接 Android，單元測試直接測這個類別）：
 *  1. [onProcessStart]（App.onCreate）：只記一行為什麼不在這裡預暖，不載任何東西。總開關或即時翻譯關著＝這個行程不預暖。
 *     行程重要度（分辨紀錄裡的原因）與寫紀錄都丟到 [io]，不佔主執行緒。
 *  2. [onForeground]（ProcessLifecycleOwner 的 ON_START，背景行程收不到）：等主畫面。
 *  3. [onFirstScreenReady]（MainActivity.ready＝第一個分頁內容出來）＋再等 [settle]，讓書庫與封面先畫完、不跟第一個畫面
 *     搶 CPU；一直等不到訊號（起始頁是「更多」、直接開進閱讀器）就等滿 [noSignalTimeout] 再判斷。主畫面還在（只是 app 退到
 *     背景再回來）就不用再等訊號：「主畫面出來了」只在 MainActivity 被銷毀時（[onScreenGone]）重設。
 *  4. 判斷（[decidePrewarmInForeground]）通過才 [PrewarmEnv.startWarmUp]。金鑰／模型檢查在 [io] 上做。
 *  5. 等的途中回到背景（[onBackground]）＝取消，下次回前景重來。
 *
 * 每個行程最多預暖一次（判斷出「開始」或「不預暖」就收工）。診斷紀錄（tag `startup`）每一步記一行：
 * `prewarm deferred|started|skipped reason=… sinceProcessStart=…ms`。
 *
 * [scope] 是主執行緒（正式版 Dispatchers.Main.immediate）：狀態只在它上面讀寫；[everForeground] 例外（任何執行緒都可以讀）。
 */
internal class StartupPrewarmController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val env: PrewarmEnv,
    private val noSignalTimeout: Duration = NO_SIGNAL_TIMEOUT,
    private val settle: Duration = SETTLE,
) {
    /** 主畫面（MainActivity）的第一個分頁內容出來了沒；MainActivity 被銷毀才重設。 */
    private val screenReady = MutableStateFlow(false)

    /** 這個行程的預暖已經有結論（開始過，或判斷不預暖）。 */
    private var finished = false

    /** 等主畫面、等 [settle] 的那個工作；回到背景就取消。 */
    private var pending: Job? = null

    /** 這個行程到過前景沒有。 */
    @Volatile
    var everForeground = false
        private set

    fun onProcessStart(masterEnabled: Boolean, liveTranslate: Boolean) {
        val since = env.sinceProcessStartMs()
        // 「不預暖」與重要度無關，當場定；要延後的才查重要度（只用來在紀錄裡分辨點開的 vs 背景叫起來的）
        val early = decidePrewarmAtProcessStart(masterEnabled, liveTranslate, importance = -1)
        if (early.action == PrewarmAction.SKIP) {
            finished = true
            log(early, since)
            return
        }
        onIo {
            val importance = env.importance()
            val decision = decidePrewarmAtProcessStart(masterEnabled, liveTranslate, importance ?: -1)
            write(decision, since, importance?.let { "importance=${importanceName(it)}" })
        }
    }

    fun onForeground() {
        everForeground = true
        if (finished) return
        pending?.cancel()
        pending = scope.launch {
            val signalled = withTimeoutOrNull(noSignalTimeout) { screenReady.first { it } } != null
            delay(settle)
            evaluate(if (signalled) PrewarmTrigger.FIRST_SCREEN else PrewarmTrigger.NO_SCREEN_SIGNAL)
        }
    }

    fun onBackground() {
        val job = pending ?: return
        pending = null
        if (job.isActive) {
            job.cancel()
            log(PrewarmDecision(PrewarmAction.DEFER, "went-background"), env.sinceProcessStartMs())
        }
    }

    fun onFirstScreenReady() {
        screenReady.value = true
    }

    fun onScreenGone() {
        screenReady.value = false
    }

    private suspend fun evaluate(trigger: PrewarmTrigger) {
        try {
            val master = env.masterEnabled()
            val live = env.liveTranslate()
            val warm = env.engineWarm()
            val loading = env.engineLoading()
            // 金鑰／模型檢查會碰檔案系統 → 放 io；其他條件已經不符時不用查
            val ready = master && live && !warm && !loading && withContext(io) { env.engineReady() }
            val decision = decidePrewarmInForeground(
                trigger = trigger,
                stillForeground = env.stillForeground(),
                masterEnabled = master,
                liveTranslate = live,
                engineReady = ready,
                engineWarm = warm,
                engineLoading = loading,
            )
            pending = null
            when (decision.action) {
                PrewarmAction.START -> {
                    finished = true
                    env.startWarmUp()
                }
                PrewarmAction.SKIP -> finished = true
                PrewarmAction.DEFER -> Unit // 已經回到背景：下次 ON_START 重來
            }
            log(decision, env.sinceProcessStartMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 預暖是錦上添花：出錯只記一行，不讓例外漏出去；第一頁照舊在要用時才載引擎
            pending = null
            finished = true
            env.logError(e)
            val since = env.sinceProcessStartMs()
            onIo { env.log("prewarm skipped reason=error ${e::class.java.simpleName} sinceProcessStart=${since}ms") }
        }
    }

    /** 記一行（時間在事件當下取，寫檔丟到 [io]）。 */
    private fun log(decision: PrewarmDecision, since: Long, extra: String? = null) {
        onIo { write(decision, since, extra) }
    }

    /** 丟到 [io] 跑，例外吞掉（只是寫紀錄、查重要度）。 */
    private fun onIo(block: () -> Unit) {
        scope.launch(io) { runCatching(block) }
    }

    private fun write(decision: PrewarmDecision, since: Long, extra: String?) {
        env.log(
            buildString {
                append(decision.logLine())
                if (extra != null) append(' ').append(extra)
                append(" sinceProcessStart=").append(since).append("ms")
            },
        )
    }

    companion object {
        /** 回到前景後最多等主畫面訊號多久（啟動畫面最長撐 5 秒，要比它長）。 */
        val NO_SIGNAL_TIMEOUT = 6.seconds

        /** 主畫面出來後再等多久才預暖：書庫查詢、封面解碼、啟動畫面淡出先做完。 */
        val SETTLE = 2.seconds
    }
}
