package eu.kanade.tachiyomi.data.nightread

import android.graphics.Bitmap
import android.graphics.Color
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.nightread.Gray
import li.joye.yakuyomi.nightread.Mask
import li.joye.yakuyomi.nightread.NightRead
import li.joye.yakuyomi.nightread.NightReadInput
import li.joye.yakuyomi.nightread.TextRegion
import li.joye.yakuyomi.nightread.ort.CharMaskOrt
import kotlin.math.max
import kotlin.math.min

/**
 * 夜讀模式的裝置端橋接：把一頁 Bitmap 走完「偵測 → 人物遮罩 → 分區重繪」，回傳暗色頁。
 *
 * 三個部分各自住在不同的地方，這裡只負責串起來與型別轉換：
 * - **偵測**用翻譯引擎既有的 DBNet（NCNN），所以夜讀不需要自己帶偵測模型。
 * - **人物遮罩**用 `:nightread-ort`（ONNX Runtime）。
 * - **重繪**用 `:nightread`（純 Kotlin，不碰 android.graphics）。
 *
 * 每頁會配置數個與頁面同尺寸的緩衝，一次只處理一頁，用完就放掉。
 */
class NightReadRenderer(
    detectorNcnnPath: String? = null,
    detectorOnnxPath: String? = null,
    yolosegPath: String,
    csegPath: String? = null,
) : AutoCloseable {

    // 偵測走兩條路：NCNN fp16（產品現況）或 ONNX int8（量化版）。前後處理共用 Detector 的
    // companion，換的只有前向那一步，A/B 才比得到量化本身。
    private val detector = detectorNcnnPath?.let { Detector(it) }
    private val detectorOrt = detectorOnnxPath?.let { DbnetOrt(it) }
    private val charMask = CharMaskOrt(yolosegPath, csegPath)

    init {
        require(detector != null || detectorOrt != null) { "至少要給一種偵測器" }
    }

    /** 一頁的耗時拆解，給上機測試看瓶頸在哪。 */
    data class Timing(val detectMs: Long, val maskMs: Long, val renderMs: Long) {
        val totalMs: Long get() = detectMs + maskMs + renderMs
    }

    class Result(val bitmap: Bitmap, val timing: Timing)

    fun render(page: Bitmap): Result {
        val w = page.width
        val h = page.height
        val pixels = IntArray(w * h)
        page.getPixels(pixels, 0, w, 0, 0, w, h)

        val t0 = System.currentTimeMillis()
        val detection = detector?.detect(page) ?: detectorOrt!!.detect(page)
        val regions = Grouping.group(detection.lines).map {
            TextRegion(
                x0 = it.x0.toInt().coerceIn(0, w),
                y0 = it.y0.toInt().coerceIn(0, h),
                x1 = it.x1.toInt().coerceIn(0, w),
                y1 = it.y1.toInt().coerceIn(0, h),
            )
        }
        val seg = bitmapToMask(detection.textMask, w, h)
        detection.textMask.recycle()
        val t1 = System.currentTimeMillis()

        val chars = charMask.detect(pixels, w, h)
        val t2 = System.currentTimeMillis()

        val result = NightRead.render(
            NightReadInput(
                gray = toGray(pixels, w, h),
                seg = seg,
                regions = regions,
                charMask = chars,
                chroma = toChroma(pixels, w, h),
            ),
        )
        val t3 = System.currentTimeMillis()

        return Result(toBitmap(result.out), Timing(t1 - t0, t2 - t1, t3 - t2))
    }

    override fun close() {
        detector?.close()
        detectorOrt?.close()
        charMask.close()
    }

    // ── 型別轉換 ─────────────────────────────────────────────────────

    /**
     * BT.601 灰階，與 `cv2.imread(..., IMREAD_GRAYSCALE)` 同一條公式。
     *
     * 研究端所有門檻（白 235、墨 128…）都是在這個灰階空間量出來的，換一條公式門檻就全歪了。
     */
    private fun toGray(pixels: IntArray, w: Int, h: Int): Gray {
        val g = Gray(w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val b = p and 0xFF
            g.data[i] = ((r * 299 + gg * 587 + b * 114 + 500) / 1000).coerceIn(0, 255)
        }
        return g
    }

    /** 每像素彩度（max−min 通道）：紙白正規化與貼紙的彩頁門用它分辨「淡彩畫底」與「灰白紙」。 */
    private fun toChroma(pixels: IntArray, w: Int, h: Int): Gray {
        val c = Gray(w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val b = p and 0xFF
            c.data[i] = max(r, max(gg, b)) - min(r, min(gg, b))
        }
        return c
    }

    private fun bitmapToMask(bmp: Bitmap, w: Int, h: Int): Mask {
        val m = Mask(w, h)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (bmp.width == w && bmp.height == h) {
            for (i in px.indices) m.data[i] = (px[i] and 0xFF) > 127
        } else {
            // 偵測器的筆畫遮罩可能是半解析度，最近鄰放大回原尺寸
            val sx = bmp.width.toDouble() / w
            val sy = bmp.height.toDouble() / h
            for (y in 0 until h) {
                val my = min(bmp.height - 1, (y * sy).toInt())
                for (x in 0 until w) {
                    val mx = min(bmp.width - 1, (x * sx).toInt())
                    m.data[y * w + x] = (px[my * bmp.width + mx] and 0xFF) > 127
                }
            }
        }
        return m
    }

    private fun toBitmap(g: Gray): Bitmap {
        val out = IntArray(g.data.size)
        for (i in out.indices) {
            val v = g.data[i]
            out[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(out, g.w, g.h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * 從 app 私有的 `files/models/` 解析出夜讀需要的模型並建 renderer。
 *
 * 偵測器沿用翻譯引擎的 NCNN DBNet，所以夜讀只需要另外備人物分割模型：`manga_seg_s*.onnx`
 * 是必要的，`cartoonseg.onnx` 可選（加了更準、代價是 228 MB）。缺必要模型就回 null，
 * 呼叫端據此安靜跳過——夜讀產生是加值功能，不該擋住翻譯。
 */
object NightReadModels {

    fun create(context: android.content.Context): NightReadRenderer? {
        val dir = java.io.File(context.filesDir, "models")
        val files = dir.takeIf { it.isDirectory }?.listFiles().orEmpty()
        fun find(pred: (String) -> Boolean) = files.firstOrNull { pred(it.name.lowercase()) }?.absolutePath
        val det = find { it.contains("dbnet") && it.endsWith(".param") } ?: return null
        val yolo = find { it.contains("manga_seg") && it.endsWith(".onnx") } ?: return null
        val cseg = find { it.contains("cartoonseg") && it.endsWith(".onnx") }
        return runCatching {
            NightReadRenderer(detectorNcnnPath = det, yolosegPath = yolo, csegPath = cseg)
        }.getOrNull()
    }

    /** 模型是否備齊到可以產生夜讀版（給設定頁顯示用，不建 session）。 */
    fun ready(context: android.content.Context): Boolean {
        val dir = java.io.File(context.filesDir, "models")
        val files = dir.takeIf { it.isDirectory }?.listFiles().orEmpty().map { it.name.lowercase() }
        return files.any { it.contains("dbnet") && it.endsWith(".param") } &&
            files.any { it.contains("manga_seg") && it.endsWith(".onnx") }
    }
}
