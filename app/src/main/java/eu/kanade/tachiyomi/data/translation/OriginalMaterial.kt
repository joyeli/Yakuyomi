package eu.kanade.tachiyomi.data.translation

/**
 * 「保留素材」裡**原圖那一份**的檔名慣例與查找（純函式；JVM 測試見 OriginalMaterialTest）。
 * 寫入端（`PageTranslator.saveOriginalBytes`／`saveMaterials`）與讀取端（重繪、「原圖（還原未翻）」）都照這裡算，別各自拼字串。
 *
 * 都放在 `<章節夾>/.yakuyomi/`，跟同頁的 `<base>.json`、`<base>.mask.png` 用同一個 base（頁檔名去掉副檔名）：
 *  - **原檔（現行，2026-10-02 起寫的都是這種）**：`<base>.orig.<頁檔的副檔名>`，例如 `012.jpg` → `012.orig.jpg`、
 *    `012.png` → `012.orig.png`。內容是下載下來那個頁檔的**位元組原樣複製**（不解碼、不重新壓縮），所以格式就是頁檔
 *    原本的格式，副檔名照頁檔的寫（大小寫也照原樣），從素材檔名就看得出原本是什麼檔。還原時整份位元組寫回頁檔。
 *  - **舊版（只讀）**：`<base>.orig.webp`，之前的版本把原圖重新壓成有損 WebP（品質 90）存的。照樣讀得到：重繪拿它解碼
 *    當底圖，還原時解碼後照頁檔的格式重新編碼寫回（跟以前一樣，不是位元組原樣）。
 *  - 暫存一律是「最終名 + `.tmp`」：先寫暫存、長度對了才改名，讀取端永遠不認暫存，被殺在半路不會留下半截的「原圖」。
 *  - **進行中記號**：「最終名 + `.wip`」的空檔（[inflightName]）。原檔存好之後、頁檔被譯圖覆蓋**之前**建立，這頁記進
 *    已翻清單之後刪掉。它在而這頁還沒進清單＝上次覆蓋到一半被打斷（頁檔可能是譯圖或半截檔）：下次翻這頁之前先把原檔
 *    寫回頁檔（[shouldRecover]），不然會把譯圖當成原檔存起來。
 *
 * **查找順序**（[resolve]）：先找原檔，沒有才找舊版。同頁兩種都在時只看原檔（寫入端存好原檔後會順手刪舊版）。
 *
 * **頁檔本身是 WebP 時兩種檔名相同**（`012.webp` → 都是 `012.orig.webp`）：分不出是原檔還是舊版重壓的，
 * 一律當「可原樣複製」處理。就算它是舊版那份有損 WebP，原樣寫回 `.webp` 頁檔也是一張格式正確的圖，
 * 比解碼後再壓一次少損一代。副檔名只差大小寫（`012.WEBP` → `012.orig.WEBP` 對 `012.orig.webp`）也算同一個檔名：
 * 裝置的共用儲存與 SAF 的 `findFile` 都不分大小寫，當成兩個檔會把剛存好的原檔當舊版刪掉。
 *
 * 不會跟夜讀檔撞名：夜讀檔是 `<完整頁檔名>.night….webp`（見 `NightPages`），這裡的檔名中段是 `.orig.`、結尾不是 `.night….webp`。
 */
object OriginalMaterial {

    /** 舊版有損 WebP 的尾綴（接在 base 之後）：`012.orig.webp`。 */
    const val LEGACY_SUFFIX = ".orig.webp"

    /** 暫存檔的尾綴（接在最終名之後）：`012.orig.jpg.tmp`。 */
    const val TMP_SUFFIX = ".tmp"

    /** 進行中記號的尾綴（接在最終名之後）：`012.orig.jpg.wip`。 */
    const val INFLIGHT_SUFFIX = ".wip"

    private const val MARK = ".orig"

    /** 頁檔名去掉副檔名（與 `<base>.json`、`<base>.mask.png` 同一個 base）。沒有副檔名就是整個檔名。 */
    fun base(pageFileName: String): String = pageFileName.substringBeforeLast('.')

    /** 頁檔的副檔名，照原樣（不轉小寫）；沒有副檔名回空字串。 */
    fun extension(pageFileName: String): String = pageFileName.substringAfterLast('.', "")

    /** 原檔素材名：`012.jpg` → `012.orig.jpg`；沒有副檔名的頁 → `012.orig`。 */
    fun exactName(pageFileName: String): String {
        val ext = extension(pageFileName)
        return if (ext.isEmpty()) base(pageFileName) + MARK else "${base(pageFileName)}$MARK.$ext"
    }

    /** 舊版素材名：`012.jpg` → `012.orig.webp`。 */
    fun legacyName(pageFileName: String): String = base(pageFileName) + LEGACY_SUFFIX

    /** 原檔素材的暫存名：`012.jpg` → `012.orig.jpg.tmp`。 */
    fun tmpName(pageFileName: String): String = exactName(pageFileName) + TMP_SUFFIX

    /** 進行中記號的檔名：`012.jpg` → `012.orig.jpg.wip`（空檔，只看在不在）。 */
    fun inflightName(pageFileName: String): String = exactName(pageFileName) + INFLIGHT_SUFFIX

    /** 兩種檔名是不是同一個（頁檔是 WebP；副檔名只差大小寫也算，見類別說明）。 */
    private fun sameAsLegacy(pageFileName: String): Boolean =
        legacyName(pageFileName).equals(exactName(pageFileName), ignoreCase = true)

    /**
     * 查到的原圖素材。[byteCopy]＝true：內容可以整份位元組寫回頁檔（原檔，或頁檔是 `.webp` 時的同名檔）；
     * false＝舊版有損 WebP，還原要解碼後照頁檔格式重新編碼。重繪兩種都是解碼來用。
     */
    data class Found(val name: String, val byteCopy: Boolean)

    /**
     * 這頁（[pageFileName]）的原圖素材是哪個檔。[exists]＝`.yakuyomi/` 裡有沒有這個檔名（呼叫端決定怎麼查）。
     * 先原檔、後舊版；都沒有 → null（這頁不能重繪、不能還原）。暫存檔、進行中記號不算。
     * 頁檔是 `012.WEBP` 而只找到小寫的 `012.orig.webp`（分大小寫的儲存上才會走到）→ 一樣可以原樣寫回。
     */
    fun resolve(pageFileName: String, exists: (String) -> Boolean): Found? {
        val exact = exactName(pageFileName)
        if (exists(exact)) return Found(exact, byteCopy = true)
        val legacy = legacyName(pageFileName)
        if (legacy != exact && exists(legacy)) return Found(legacy, byteCopy = sameAsLegacy(pageFileName))
        return null
    }

    /** 同上，[names]＝`.yakuyomi/` 內的檔名集合。 */
    fun resolve(pageFileName: String, names: Set<String>): Found? = resolve(pageFileName) { it in names }

    /**
     * 存好原檔之後可以刪掉的舊版檔名；頁檔是 WebP（兩種檔名相同，副檔名只差大小寫也算）時沒有東西可刪 → null。
     * 大小寫要當成相同：`findFile` 不分大小寫，拿 `012.orig.webp` 去找會找到剛存好的 `012.orig.WEBP`、把它刪掉。
     */
    fun supersededLegacyName(pageFileName: String): String? =
        legacyName(pageFileName).takeIf { !sameAsLegacy(pageFileName) }

    /**
     * 翻譯前要不要先把素材裡的原檔寫回頁檔：這頁還沒進已翻清單（[inManifest]＝false）、進行中記號在（[hasMarker]）、
     * 原檔也在（[hasExactOriginal]）＝上次存好原檔後覆蓋頁檔到一半被打斷，頁檔現在可能是譯圖或半截檔。
     * 已在清單裡的頁不管記號（那是清單寫完、記號還沒刪就被打斷留下的，頁檔是完整的譯圖）。
     */
    fun shouldRecover(inManifest: Boolean, hasMarker: Boolean, hasExactOriginal: Boolean): Boolean =
        !inManifest && hasMarker && hasExactOriginal

    /**
     * 頁檔現在的內容是不是**我們自己寫的成品**（譯圖、重繪圖，或還原寫回的原圖），而且素材裡留著它的原圖。
     * 是的話再翻這頁（reader「翻譯這頁」）要拿素材的原圖當輸入、原圖素材不重存；不是（頁檔被使用者或擷取功能換過，
     * 或根本沒有原圖素材）才把頁檔當新的原圖。
     *
     * 判準：這頁在已翻清單裡、有原圖素材，而且頁檔的修改時間不晚於同頁素材 json 的（我們每次寫頁檔之後都會接著寫 json，
     * 所以成品的頁檔不會比 json 新；使用者之後換掉頁檔，頁檔就比 json 新）。時間讀不到（0）就當不是，行為同以前。
     */
    fun pageIsOwnOutput(inManifest: Boolean, hasOriginal: Boolean, pageMtime: Long, jsonMtime: Long): Boolean =
        inManifest && hasOriginal && pageMtime > 0L && jsonMtime > 0L && pageMtime <= jsonMtime

    /**
     * 原檔素材名 → 原本頁檔的副檔名（`012.orig.jpg` → `jpg`、`012.orig.webp` → `webp`）；
     * 不是原圖素材（含暫存、夜讀檔、遮罩、json）→ null。給列舉 `.yakuyomi/` 的程式分辨用。
     */
    fun pageExtensionOf(materialName: String): String? =
        MATERIAL.find(materialName)?.groupValues?.get(2)

    /** 是不是原圖素材的最終檔（原檔或舊版，不含暫存）。 */
    fun isOriginalMaterial(name: String): Boolean = MATERIAL.containsMatchIn(name)

    /** `<base>.orig.<ext>`：base 至少一個字、ext 是不含點的一段。 */
    private val MATERIAL = Regex("""^(.+)\.orig\.([^.]+)$""")
}
