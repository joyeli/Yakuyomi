package eu.kanade.tachiyomi.data.translation

import java.util.Locale

/**
 * 翻譯路徑的頁圖像素上限（純函式；JVM 測試見 PageSizeCapTest）。使用者 2026-10-07 拍板：超過 40 MPx 的頁不翻譯。
 *
 * 為什麼要有：頁圖整張解成 ARGB 進引擎，40 MPx 就要 160 MB，再加引擎內部的縮圖、遮罩、去字與譯圖，一張超大掃圖或
 * 超長條漫就足以讓翻譯 OOM（夜讀那邊另有自己的降採樣，不歸這裡管）。
 *
 * 做法：解碼**之前**先用 inJustDecodeBounds 只讀檔頭量寬高（`PageTranslator.probePageSize`），交給 [check] 判：
 *  - [Verdict.TooLarge]：不解碼、不翻，原圖不動（§11：永遠不用比原圖更糟的東西覆蓋原檔），原因寫進該話錯誤檔。
 *    整章佇列裡算「略過」（記進已翻清單、不重試——尺寸不會變，重試結果一樣）；單頁「翻譯這頁」回 TOO_LARGE 給 reader 提示。
 *  - [Verdict.Unreadable]：讀不出尺寸（檔案壞掉、不是支援的圖檔、串流開不了）——跟解碼失敗同一類，留原圖、下次重試。
 *  - [Verdict.Ok]：照常解碼翻譯。
 *
 * reader 的提示字串（`reader_translate_page_too_large`）把上限寫成「40 megapixels／4000 萬畫素」：改 [MAX_PIXELS] 要一起改字串。
 */
object PageSizeCap {

    /** 上限（像素數）：寬 × 高 **大於**它才擋；剛好等於照翻。 */
    const val MAX_PIXELS: Long = 40_000_000L

    /** [check] 的判定。 */
    sealed interface Verdict {
        data object Ok : Verdict

        data class TooLarge(val width: Int, val height: Int) : Verdict

        data object Unreadable : Verdict
    }

    /** 依檔頭量到的寬高判定。寬或高 ≤ 0＝檔頭讀不出尺寸。乘法用 Long，不會溢位。 */
    fun check(width: Int, height: Int): Verdict = when {
        width <= 0 || height <= 0 -> Verdict.Unreadable
        width.toLong() * height.toLong() > MAX_PIXELS -> Verdict.TooLarge(width, height)
        else -> Verdict.Ok
    }

    /** 錯誤檔的原因文字（頁名之後那一欄；不含換行、不含 tab）。百萬像素取一位小數。開頭固定是 [REASON_PREFIX]。 */
    fun tooLargeReason(width: Int, height: Int): String {
        val mp = String.format(Locale.ROOT, "%.1f", width.toLong() * height.toLong() / 1_000_000.0)
        val cap = MAX_PIXELS / 1_000_000L
        return "$REASON_PREFIX（$width×$height，約 $mp 百萬像素，上限 $cap 百萬像素），不翻譯、保留原圖"
    }

    /**
     * 錯誤檔裡這一行的原因是不是「頁圖太大」（[tooLargeReason]）。太大的頁記進已翻清單、之後不會再處理，整章再跑一輪時錯誤檔
     * 整檔重寫，要靠這個把它們的原因行留下來（`PageTranslator.translateChapter`）。
     */
    fun isTooLargeReason(reason: String): Boolean = reason.startsWith(REASON_PREFIX)

    private const val REASON_PREFIX = "頁圖太大"
}
