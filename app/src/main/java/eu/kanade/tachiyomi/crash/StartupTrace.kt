package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * 開 app 到書庫出現這段的診斷點（2026-10-06 書庫轉圈調查：轉圈時間隨「瀏覽過但沒加入書庫」的本數變長）。
 *
 * 只在 [TraceLog] 開著時做事：每個入口第一行先看 [TraceLog.isEnabled]，關著就 return——不量時間、不碰資料庫、
 * 不開協程；書庫 ViewModel 拿到的 [LibraryLoadTrace] 是 null，之後的 `?.` 全部跳過。
 *
 * 記的東西（tag `startup`）：
 *  - `app.onCreate`：距行程啟動幾毫秒（量在診斷紀錄 init 之前）、`traceInit`＝[TraceLog.init] 本身花的毫秒（輪替上次紀錄、
 *    寫表頭）、行程重要度（使用者點開的會是 foreground，背景工作先叫起來的不是）。
 *  - `activity.onCreate`：這次是全新行程、被殺後還原、還是既有行程（[classifyActivityStart]）；Android 15 起再附系統記的
 *    啟動類型與原因。
 *  - `library.viewModel`／`library.firstResult`（書庫查詢第一次給結果：列數、從訂閱到拿到清單的毫秒，含排隊等連線、
 *    查詢本身與每列轉物件）／`library.spinnerEnd`（isLoading 變 false，距行程啟動毫秒；`shown`＝套完篩選與搜尋後
 *    顯示的本數，不是書庫總數，書庫總數看 firstResult 的 rows）。
 *  - 書庫出現後、每個行程一次、背景延遲 [REPORT_DELAY]：資料量（漫畫／非書庫漫畫／章節／非書庫章節）、資料庫與 -wal
 *    檔大小、預設 SharedPreferences 檔大小、翻譯／夜讀佇列與引擎預暖當下在不在跑，最後新舊兩句書庫 SQL 各跑一次的毫秒
 *    （透過同一個 driver、結果丟掉），讓真機看得到改寫省了多少。
 *
 * 只記數字與狀態名，不記標題、網址、金鑰。
 *
 * 讀數時注意：診斷紀錄自己也有成本——[TraceLog.init] 在主執行緒上（`traceInit`），之後每記一行都整檔重寫——所以
 * `app.onCreate` 之後的 sinceProcessStart 都含這些時間。比較時看兩個點之間的差，不要直接拿絕對值跟沒開紀錄時比。
 */
object StartupTrace {

    private const val TAG = "startup"

    /** 書庫顯示後等多久才跑背景報告（讓第一畫面、封面先載）。 */
    private val REPORT_DELAY = 2.seconds

    /** 資料庫檔名，與 AppModule 的 `AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.db")` 同一個。 */
    private const val DB_NAME = "tachiyomi.db"

    // Yakuyomi merge（mihon v0.21，upstream 93cc07511 把 mangas／chapters／favorite 改名成 manga／chapter／
    // user_favorite_at，libraryView 本身也修好了）：下面兩句是手寫字串，SQLDelight 編譯時不檢查，merge 後會在執行時才失敗。
    // merge 時刪掉 OLD_LIBRARY_SQL 與 report 第 4 步的新舊比較（那時舊 view 已經是修好的版本，比了沒意義），
    // COUNTS_SQL 照新 schema 改寫（manga／chapter／user_favorite_at IS NOT NULL）。

    /**
     * 改寫前的書庫查詢（libraryView.sq 的 `library:` 原本就是這句）。libraryView 這個 view 沒改，所以這句跑的就是舊行為：
     * 先把整個資料庫的章節、分類都聚合完，最後才丟掉不在書庫的本。
     */
    private const val OLD_LIBRARY_SQL = "SELECT * FROM libraryView"

    /** 四個數：漫畫總數、非書庫漫畫、章節總數、書庫漫畫的章節（非書庫章節＝總數減它，免得把非書庫章節整個掃一遍）。 */
    private const val COUNTS_SQL = "SELECT (SELECT count(*) FROM mangas), " +
        "(SELECT count(*) FROM mangas WHERE favorite = 0), " +
        "(SELECT count(*) FROM chapters), " +
        "(SELECT count(*) FROM chapters WHERE manga_id IN (SELECT _id FROM mangas WHERE favorite = 1))"

    private val firstActivity = AtomicBoolean(true)
    private val reportStarted = AtomicBoolean(false)

    /** 這個行程啟動時診斷紀錄就開著（[onAppCreated] 有記到）；沒有＝紀錄是中途從設定頁打開的。 */
    @Volatile
    private var processStartTraced = false

    /** 最近一次 MainActivity.onCreate 的時間（每個新畫面都更新，書庫的 sinceActivityCreate 才是從這次畫面算）。 */
    @Volatile
    private var activityCreatedAt = 0L

    private fun sinceProcessStartMs(): Long = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

    private fun sinceActivityCreate(now: Long): String =
        activityCreatedAt.takeIf { it > 0L }?.let { "${now - it}ms" } ?: "n/a"

    /**
     * App.onCreate（診斷開著、[TraceLog.init] 之後）。sinceProcessStart 量在 init 開始那一刻，init 自己花的另外記成
     * traceInit，免得把診斷紀錄的成本算進啟動時間。
     */
    fun onAppCreated() {
        if (!TraceLog.isEnabled) return
        processStartTraced = true
        val importance = runCatching {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        }.getOrDefault(-1)
        val beforeInit = TraceLog.lastInitStartedAt.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        TraceLog.log(
            TAG,
            "app.onCreate sinceProcessStart=${beforeInit - Process.getStartUptimeMillis()}ms " +
                "traceInit=${TraceLog.lastInitMs}ms importance=${importanceName(importance)}",
        )
    }

    /** MainActivity.onCreate 一進來。 */
    fun onActivityCreated(context: Context, hasSavedState: Boolean) {
        if (!TraceLog.isEnabled) return
        val now = SystemClock.uptimeMillis()
        val firstSeen = firstActivity.getAndSet(false)
        activityCreatedAt = now
        val startInfo = if (firstSeen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { StartInfoApi35.describe(context) }.getOrNull().orEmpty()
        } else {
            ""
        }
        TraceLog.log(
            TAG,
            "activity.onCreate start=${classifyActivityStart(firstSeen, hasSavedState, processStartTraced)} " +
                "savedState=$hasSavedState sinceProcessStart=${sinceProcessStartMs()}ms$startInfo",
        )
    }

    /**
     * Android 15 的 ApplicationStartInfo 呼叫放在自己的類別裡：[StartupTrace] 本身不直接引用 API 35 的方法，舊版 Android
     * 載入它時就不用為了這幾個方法多做一次執行期檢查。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private object StartInfoApi35 {
        fun describe(context: Context): String? = context.getSystemService<ActivityManager>()
            ?.getHistoricalProcessStartReasons(1)
            ?.firstOrNull()
            ?.takeIf { it.pid == Process.myPid() }
            ?.let { " startType=${startTypeName(it.startType)} reason=${startReasonName(it.reason)}" }
    }

    /** 書庫 ViewModel 建立時呼叫；診斷關著回 null。 */
    fun libraryViewModelCreated(): LibraryLoadTrace? {
        if (!TraceLog.isEnabled) return null
        val now = SystemClock.uptimeMillis()
        TraceLog.log(
            TAG,
            "library.viewModel created sinceProcessStart=${sinceProcessStartMs()}ms " +
                "sinceActivityCreate=${sinceActivityCreate(now)}",
        )
        return LibraryLoadTrace(now)
    }

    /** 一個書庫 ViewModel 的載入時間點；只在診斷開著時存在。 */
    class LibraryLoadTrace internal constructor(private val createdAt: Long) {
        private val firstResultLogged = AtomicBoolean(false)
        private val spinnerEndLogged = AtomicBoolean(false)

        /** 包住書庫查詢的 Flow：第一次給結果時記列數與毫秒（從開始收集算）。之後原樣轉發。 */
        fun <T> firstResult(source: Flow<List<T>>): Flow<List<T>> = flow {
            val start = SystemClock.uptimeMillis()
            source.collect { list ->
                if (firstResultLogged.compareAndSet(false, true)) {
                    val now = SystemClock.uptimeMillis()
                    TraceLog.log(
                        TAG,
                        "library.firstResult rows=${list.size} ms=${now - start} (collect -> first list: " +
                            "connection wait + query + row mapping) sinceViewModel=${now - createdAt}ms " +
                            "sinceProcessStart=${sinceProcessStartMs()}ms",
                    )
                }
                emit(list)
            }
        }

        /**
         * isLoading 第一次變 false（轉圈結束、書庫畫面出來）。之後排背景報告（每個行程一次）。
         * [shown]＝套完篩選與搜尋後要顯示的本數（不是書庫總數）。
         */
        fun onSpinnerEnd(shown: Int) {
            if (!spinnerEndLogged.compareAndSet(false, true)) return
            val now = SystemClock.uptimeMillis()
            TraceLog.log(
                TAG,
                "library.spinnerEnd shown=$shown sinceProcessStart=${sinceProcessStartMs()}ms " +
                    "sinceActivityCreate=${sinceActivityCreate(now)} sinceViewModel=${now - createdAt}ms",
            )
            startReportOnce()
        }
    }

    private fun startReportOnce() {
        if (!reportStarted.compareAndSet(false, true)) return
        // 每個行程最多一次，用時才建 scope（診斷關著時這個物件不建任何協程的東西）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(REPORT_DELAY)
            if (!TraceLog.isEnabled) return@launch
            // 外層再包一次：拿不到 Application 之類步驟外的錯誤也只記一行，不讓例外漏出去讓 app 閃退
            runCatching { report(Injekt.get<Application>()) }
                .onFailure { TraceLog.log(TAG, "report failed: ${it::class.java.simpleName}") }
        }
    }

    /** 報告的每一步各自接住錯誤：一步失敗只記一行 `report.<step> failed`，其他步照跑。 */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TraceLog.log(TAG, "report.$name failed: ${e::class.java.simpleName}")
        }
    }

    private suspend fun report(context: Context) {
        val driver = Injekt.get<SqlDriver>()

        // 1) 資料量
        step("counts") {
            val t = SystemClock.uptimeMillis()
            val c = driver.executeQuery(null, COUNTS_SQL, longsMapper(4), 0).await()
            if (c.size == 4) {
                TraceLog.log(
                    TAG,
                    "report.counts manga=${c[0]} nonLibraryManga=${c[1]} chapters=${c[2]} " +
                        "nonLibraryChapters=${c[2] - c[3]} (${SystemClock.uptimeMillis() - t}ms)",
                )
            }
        }

        // 2) 檔案大小
        step("files") {
            val db = context.getDatabasePath(DB_NAME)
            val wal = File(db.path + "-wal")
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val prefs = File(prefsDir, "${context.packageName}_preferences.xml")
            TraceLog.log(
                TAG,
                "report.files db=${formatSize(db.sizeOrNull())} wal=${formatSize(wal.sizeOrNull())} " +
                    "defaultPrefs=${formatSize(prefs.sizeOrNull())}",
            )
        }

        // 3) 這時候背景在忙什麼（翻譯／夜讀佇列、引擎預暖）
        step("busy") {
            val manager = runCatching { Injekt.get<TranslationManager>() }.getOrNull()
            val engine = runCatching { Injekt.get<TranslationEngineService>() }.getOrNull()
            val queue = manager?.queueState?.value.orEmpty().map { it.kind.night to it.status }
            TraceLog.log(
                TAG,
                "report.busy ${summarizeQueue(queue)} translatePaused=${manager?.isTranslatePaused?.value} " +
                    "nightPaused=${manager?.isNightPaused?.value} engineLoading=${engine?.loading?.value} " +
                    "engineWarm=${engine?.warm?.value}",
            )
        }

        // 4) 新舊書庫 SQL 各一次（新的先跑：它剛載過、頁面是熱的；舊的要多讀非書庫章節，那就是真正的差距）
        step("librarySql") {
            var t = SystemClock.uptimeMillis()
            val newRows = Injekt.get<Database>().libraryViewQueries.library().execute(countRows).await()
            val newMs = SystemClock.uptimeMillis() - t
            t = SystemClock.uptimeMillis()
            val oldRows = driver.executeQuery(null, OLD_LIBRARY_SQL, countRows, 0).await()
            val oldMs = SystemClock.uptimeMillis() - t
            TraceLog.log(TAG, "report.librarySql new=${newMs}ms rows=$newRows | old=${oldMs}ms rows=$oldRows")
        }
    }

    private fun File.sizeOrNull(): Long? = if (isFile) length() else null

    /** 數列數、不讀欄位（新舊兩句同樣處理，比的只有查詢本身）。寫法照 sqldelight 的 awaitAsList：同步 cursor 直接回值。 */
    private val countRows: (SqlCursor) -> QueryResult<Long> = { cursor ->
        val first = cursor.next()
        if (first is QueryResult.Value) {
            var n = 0L
            if (first.value) {
                n++
                while (cursor.next().value) n++
            }
            QueryResult.Value(n)
        } else {
            QueryResult.AsyncValue {
                var n = 0L
                if (first.await()) {
                    n++
                    while (cursor.next().await()) n++
                }
                n
            }
        }
    }

    /** 單列、[n] 個整數欄。 */
    private fun longsMapper(n: Int): (SqlCursor) -> QueryResult<List<Long>> = { cursor ->
        val first = cursor.next()
        if (first is QueryResult.Value) {
            QueryResult.Value(if (first.value) List(n) { cursor.getLong(it) ?: 0L } else emptyList())
        } else {
            QueryResult.AsyncValue {
                if (first.await()) List(n) { cursor.getLong(it) ?: 0L } else emptyList()
            }
        }
    }
}
