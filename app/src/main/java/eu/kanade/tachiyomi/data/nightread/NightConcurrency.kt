package eu.kanade.tachiyomi.data.nightread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 夜讀多頁並行的記憶體算術（純 Kotlin、JVM 可測）。
 *
 * 每頁在飛約 58 B/px 的 Java heap：桌面 harness 實測 2.59 MPx 頁的重繪內部約 110–115 MB，加包裝層 px/gray/chroma/seg/char
 * 約 36 MB（nightread research `nightpar_design`）。Bitmap 像素在 native（minSdk 26），不算進來。
 * 預算＝`maxMemory × 0.75 − 64 MB`：512 MB largeHeap → 320 MB → 參考頁（2.6 MPx，約 152 MB）放得下 2 頁。
 * 這只是上限；每頁放行時還要看在飛頁的預估總和與實測的 heap 用量（見 [NightGovernor.ChapterGate.admit]）。
 * 多檔一次產生（`NightReadRenderer.renderTiers`，產品兩檔）照用 58 B/px：分析只做一次、各檔串流交出（一次只有一張輸出 Bitmap，
 * 共用分析的快取存 1 bit/px），桌面 JVM 最低可跑 heap 與單檔 render 相同（nightread docs/DECISIONS 2026-10-01）。
 * 2026-10-06 nightread 加速四批（輸出逐位元不變）後重量依序版最低可跑 heap：2.6 MPx 譯後頁 147–151 MB、7.2 MPx 380 MB，
 * 沒有變大，58 B/px 照用。引擎的頁內並行（`NightReadRenderer` 的 parallel）這裡沒開：開了每頁尖峰再多 11–27 MB（2.6 MPx
 * 頁，最多約 10.4 B/px，跟排程有關；2026-10-07 審查另一套量法量到的上緣，c362_008 池 3 條要 178–180 MB），兩頁就放不進 320 MB
 * 的預算。要開得把 [BYTES_PER_PX] 加到**至少 70**，或只在「唯一在飛的頁」用。
 */
object NightConcurrency {
    const val MB = 1024L * 1024

    /** 同時處理頁數的硬上限（再多就卡在 NCNN 全域鎖與 GC 上，也放不進 512 MB）。 */
    const val MAX_PAGES = 3

    /**
     * 1 緒不進鎖的模型組（FREE）最多幾頁並行。FREE 只在讓路時用（那時本來就一次一頁），所以固定 1：開組不必暖機
     * （沒有「多頁同時打進還沒初始化的 Net」，暖機又等於在單緒、nice 9 下白跑一頁的推論），Net 的 PoolAllocator 也只長到
     * 一份前向峰值。換組失敗、這章停在 FREE 又回到 EXCLUSIVE 的邊角也照樣一次一頁。
     */
    const val FREE_MAX_PAGES = 1

    const val BYTES_PER_PX = 58L

    /** 算自動頁數用的參考頁像素數（1351×1920）。 */
    const val REF_PX = 2_600_000L

    /**
     * 放行第二頁起要留的 heap 餘裕。翻譯在跑時（YIELD）一次只放一頁、在飛＝0 才放，不看 heap，所以沒有「翻譯在跑再多留」
     * 這一項——翻譯頁要的 heap 靠讓路時把非最早的頁丟回待做放掉（見 [NightGovernor.ChapterGate.keep]）。
     */
    const val RESERVE_BYTES = 96 * MB

    /** 偏好值「自動」。 */
    const val AUTO = "auto"

    /**
     * 一頁在飛的 heap 預估；[workPx]＝實際跑的像素數（解碼後、上限 MAX_PIXELS）。[inpaintMask]＝譯後頁、重繪多帶翻譯素材的
     * 去字遮罩（[inpaintMaskBytes]）。
     */
    fun estBytes(workPx: Long, inpaintMask: Boolean = false): Long =
        workPx * BYTES_PER_PX + 8 * MB + if (inpaintMask) inpaintMaskBytes(workPx) else 0L

    /**
     * 譯後頁多帶的去字遮罩（nightread 規則版本 4 的 `NightReadInput.inpaintMask`）：引擎轉成的 1 B/px 布林遮罩＋函式庫內部的
     * 位元版 1/8 B/px（nightread docs/DECISIONS 規則版本 4 一節；桌面量最低可跑 heap：譯後 c362_014 157 → 160 MB）。遮罩
     * Bitmap 的像素在 native，不算。不併進 [BYTES_PER_PX]：日文頁沒有這份；參考頁兩頁（帶遮罩約 162 MB／頁）仍放得進 512 MB
     * heap 的 320 MB 預算。
     */
    fun inpaintMaskBytes(workPx: Long): Long = workPx + (workPx + 7) / 8

    fun heapBudget(maxMemory: Long): Long = (maxMemory * 0.75).toLong() - 64 * MB

    /** 這個 heap 放得下幾頁參考頁（1..[MAX_PAGES]）。設定頁的選項也只列到這裡。 */
    fun heapPageCap(maxMemory: Long = Runtime.getRuntime().maxMemory()): Int =
        (heapBudget(maxMemory) / estBytes(REF_PX)).toInt().coerceIn(1, MAX_PAGES)

    /**
     * 偏好（`auto`／`1`..`3`，存字串）→ 同時處理頁數：自動＝`min(3, heapPageCap)`；數字再夾到 heapPageCap
     * （匯入的備份可能帶來這台放不下的值）。只在 EXCLUSIVE（只有夜讀在跑）時生效，見 [NightGovernor]。
     */
    fun pagesCap(pref: String, maxMemory: Long = Runtime.getRuntime().maxMemory()): Int {
        val cap = heapPageCap(maxMemory)
        return if (pref == AUTO) minOf(MAX_PAGES, cap) else pref.toIntOrNull()?.coerceIn(1, cap) ?: cap
    }
}

/**
 * 夜讀頁的專用執行緒池（`yaku-night-N`，最多 [NightConcurrency.MAX_PAGES] 條、閒置 30 s 收掉）。
 *
 * 不能用 Dispatchers.Default／IO：翻譯的逐頁併發與 OCR 也在那些執行緒上，[NightGovernor] 改夜讀執行緒的 nice 會連帶拖慢翻譯。
 * 執行緒以 Java 預設優先權出生（**不能**用 MIN_PRIORITY：Android 會對應到 nice 19 加背景 profile），實際 nice 由 governor 依模式設。
 */
object NightWorkers {
    private val seq = AtomicInteger(0)

    val dispatcher: CoroutineDispatcher by lazy {
        ThreadPoolExecutor(
            NightConcurrency.MAX_PAGES,
            NightConcurrency.MAX_PAGES,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { r ->
            Thread(r, "yaku-night-${seq.incrementAndGet()}").apply { priority = Thread.NORM_PRIORITY }
        }.apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()
    }
}
