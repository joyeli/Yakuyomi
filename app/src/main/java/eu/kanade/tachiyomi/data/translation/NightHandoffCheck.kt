package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * debug 建置的一次性比對（軌道 B2：合併輪把譯圖位元組、文字框與去字遮罩在記憶體裡交給夜讀，S3／S4；夜讀檔交給編碼執行緒寫，
 * S5）：每個行程第一頁用交接做完的夜讀頁，記下交接那邊的頁像素、文字框、遮罩雜湊；換好檔之後照以前的路徑從檔案再做一次
 * （`PageTranslator.verifyHandoff`：讀頁檔解碼、從素材檔挑框與遮罩、同一組模型重繪、在同一條執行緒上壓 WebP），比頁像素、框、
 * 遮罩，以及兩檔的位元組跟編碼執行緒寫出的夜讀檔，紀錄 `verify2=same|diff`。正式版不跑（[claim] 一律 false）。紀錄只有雜湊
 * 與計數，沒有任何金鑰或內容。判定是純函式（[verdict]，JVM 測試見 NightHandoffCheckTest）。
 */
internal object NightHandoffCheck {
    private val claimed = AtomicBoolean(false)

    /** 搶這個行程唯一的一次比對（debug 建置；多頁同時交接時只有一頁拿到）。 */
    fun claim(): Boolean = BuildConfig.DEBUG && claimed.compareAndSet(false, true)

    /** 搶到名額的那頁沒走到比對（過期、放棄、例外、彩頁略過）：名額還回去，之後交接的頁再比。 */
    fun unclaim() {
        claimed.set(false)
    }

    /** 交接那邊：交過來的位元組解碼後的頁像素雜湊、交過來的文字框、遮罩像素雜湊（沒有遮罩＝`none`）。 */
    class Record(val pageHash: String, val quads: List<List<List<Float>>>, val maskHash: String)

    /**
     * 從檔案重做那邊：頁檔解碼後的像素雜湊、挑到的文字框跟交接的是否逐點相同（[quadsFrom]＝挑到哪一份）、遮罩像素雜湊、
     * 兩檔（夜讀檔位 fileKey → 壓成的位元組雜湊；與前一檔相同、沒交圖＝`none`）。
     */
    class FileSide(
        val pageHash: String,
        val quadsSame: Boolean,
        val quadsFrom: String,
        val maskHash: String,
        val tiers: Map<String, String>,
    )

    /** 比對結果：[same]＝全部相同；[summary]＝紀錄用（各項 same/diff 與雜湊）。 */
    class Verdict(val same: Boolean, val summary: String)

    /**
     * [rec]（交接）對 [file]（從檔案重做），兩檔位元組再對 [written]（編碼執行緒寫出的夜讀檔，fileKey → 雜湊，沒有檔＝`none`）。
     * 兩邊的檔位集合要一樣、而且至少有一檔。
     */
    fun verdict(rec: Record, file: FileSide, written: Map<String, String>): Verdict {
        val pageSame = rec.pageHash == file.pageHash
        val maskSame = rec.maskHash == file.maskHash
        val keys = (file.tiers.keys + written.keys).toSortedSet()
        val tiersSame = keys.isNotEmpty() && keys.all { k -> file.tiers[k] != null && file.tiers[k] == written[k] }
        val same = pageSame && file.quadsSame && maskSame && tiersSame
        val tierText = keys.joinToString(" ") { k -> "$k=${file.tiers[k] ?: "?"}/${written[k] ?: "?"}" }
        val summary = "page=${sd(pageSame)}[${rec.pageHash}/${file.pageHash}] " +
            "quads=${sd(file.quadsSame)}(${rec.quads.size},${file.quadsFrom}) " +
            "mask=${sd(maskSame)}[${rec.maskHash}/${file.maskHash}] tiers=${sd(tiersSame)}[$tierText]"
        return Verdict(same, summary)
    }

    /** 位元組的 SHA-256 前 8 位元組（hex）。 */
    fun bytesHash(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun sd(same: Boolean) = if (same) "same" else "diff"

    private fun hex(d: ByteArray): String = d.take(8).joinToString("") { "%02x".format(it) }
}
