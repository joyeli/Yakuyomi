package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import eu.kanade.tachiyomi.BuildConfig
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.nightread.NightTier
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * debug 建置的一次性比對（S1：合併輪夜讀借翻譯引擎的偵測器）：每個行程第一頁「偵測是借來的」夜讀頁，記下借來的偵測（行數、
 * 行框雜湊、textMask 雜湊）與兩檔像素雜湊，再用自己建的偵測器重做一次比（`PageTranslator.verifySharedDetector`），
 * 紀錄 `verify=same|diff`。正式版不跑（[pending] 一律 false）。紀錄只有雜湊與計數，沒有任何金鑰或內容。
 */
internal object NightShareCheck {
    private val claimed = AtomicBoolean(false)

    /** 這個行程還沒比過（debug 建置）。 */
    fun pending(): Boolean = BuildConfig.DEBUG && !claimed.get()

    /** 搶這個行程唯一的一次比對（多頁同時借時只有一頁拿到）。 */
    fun claim(): Boolean = BuildConfig.DEBUG && claimed.compareAndSet(false, true)

    /**
     * 搶到名額的那頁沒走到比對（暫停／取消在重繪前放棄、推論或重繪丟例外、OOM）：名額還回去，之後借來的頁再比。
     * 不還的話這個行程就永遠不會有 `verify=` 那行，手機驗收看不出是沒比還是忘了印。
     */
    fun unclaim() {
        claimed.set(false)
    }

    /** 一邊（借的／自己的）的偵測與兩檔。 */
    class Side {
        private var lines = -1
        private var quads = ""
        private var mask = ""
        private val tiers = LinkedHashMap<String, String>()

        fun recordDetection(d: Detection) {
            lines = d.lines.size
            quads = linesHash(d.lines)
            mask = bitmapHash(d.textMask)
        }

        /** 兩檔依序；null＝與前一檔相同、引擎沒交圖。 */
        fun recordTier(tier: NightTier, bmp: Bitmap?) {
            tiers[tier.key] = bmp?.let(::bitmapHash) ?: "same-as-prev"
        }

        fun sameAs(o: Side): Boolean =
            lines == o.lines && quads == o.quads && mask == o.mask && tiers == o.tiers && tiers.isNotEmpty()

        fun summary(): String =
            "lines=$lines quads=$quads mask=$mask tiers=" + tiers.entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    /** Bitmap 像素位元組的 SHA-256 前 8 位元組（hex）。緩衝用 direct buffer，不吃 Java heap。 */
    fun bitmapHash(b: Bitmap): String {
        val buf = ByteBuffer.allocateDirect(b.byteCount)
        b.copyPixelsToBuffer(buf)
        buf.flip()
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${b.width}x${b.height}:${b.config}".toByteArray())
        md.update(buf)
        return hex(md.digest())
    }

    /** 行框（四點座標與分數的 float 位元）的 SHA-256 前 8 位元組（hex）。 */
    fun linesHash(lines: List<TextLine>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bb = ByteBuffer.allocate(4)
        fun put(f: Float) {
            bb.clear()
            bb.putInt(java.lang.Float.floatToRawIntBits(f))
            md.update(bb.array())
        }
        for (l in lines) {
            for (p in l.quad) {
                put(p.x)
                put(p.y)
            }
            put(l.score)
        }
        return hex(md.digest())
    }

    private fun hex(d: ByteArray): String = d.take(8).joinToString("") { "%02x".format(it) }
}
