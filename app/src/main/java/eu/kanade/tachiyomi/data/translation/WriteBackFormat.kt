package eu.kanade.tachiyomi.data.translation

/**
 * 譯圖／重繪圖寫回頁檔時用的編碼格式，跟著頁檔的副檔名走（純函式；JVM 測試見 WriteBackFormatTest）。使用者 2026-10-07 拍板：
 *  - `.png` → PNG（無損，品質參數不管用）。
 *  - `.webp` → 有損 WebP，品質跟 JPEG 那條一樣（[QUALITY]）；API ≥ 30 用 `WEBP_LOSSY`，更舊的用 `WEBP`（品質 < 100 就是有損），
 *    對應到 `Bitmap.CompressFormat` 在 `PageTranslator.writeBack` 做（這裡不碰 android.graphics，JVM 測試才跑得動）。
 *  - `.jpg`／`.jpeg`／其他 → JPEG（跟以前一樣）。
 * 以前 `.webp` 頁也寫成 JPEG：檔名跟內容對不上，而且通常比 WebP 大。
 * 副檔名不分大小寫。沒有副檔名或檔名是 null → JPEG。
 *
 * [maxSide]＝這個格式寫得下的最長邊。編碼器遇到超過的尺寸會直接失敗；寫回要先用 [fits] 擋，不然截斷寫已經把頁檔清空了
 * 才發現編不出來。下載來的頁副檔名照內容取，譯圖跟原圖一樣大，所以一定寫得下；只有檔名跟內容對不上的本機檔會碰到。
 */
enum class WriteBackFormat(val maxSide: Int) {
    /** PNG 規格上限是 2^31−1，實際上不設限。 */
    PNG(Int.MAX_VALUE),

    /** libwebp 的 WEBP_MAX_DIMENSION。 */
    WEBP(16_383),

    /** libjpeg-turbo 的 JPEG_MAX_DIMENSION。 */
    JPEG(65_500),
    ;

    /** 寬高都在 1..[maxSide] 之內才寫得下。 */
    fun fits(width: Int, height: Int): Boolean = width in 1..maxSide && height in 1..maxSide

    companion object {
        /** JPEG 與 WebP 共用的品質（PNG 不看）。 */
        const val QUALITY = 92

        fun forFileName(name: String?): WriteBackFormat {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "png" -> PNG
                "webp" -> WEBP
                else -> JPEG
            }
        }
    }
}
