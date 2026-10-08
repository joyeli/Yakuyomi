package eu.kanade.tachiyomi.data.nightread

import li.joye.yakuyomi.nightread.NightTier

/**
 * 產品的夜讀檔位：**兩檔**（2026-10-01 定案）。「標準」＝nightread 的 [NightTier.L2]（預設），「更多」＝[NightTier.L3]。
 * L1 從產品拿掉：47 頁裡 L1 與 L2 只有 14 頁不同、守護框違規同為 12/664；L2 與 L3 有 25 頁不同，真正有取捨的是 L3。
 * 函式庫的 [NightTier] 照舊三檔（研究用），檔位參數的單一來源仍是 [NightTier.apply]。
 *
 * - [tier]：交給引擎的檔位（產生時 [engineTiers]＝`[L2, L3]`，依序；第一檔一定落地＝完成標記）。
 * - [prefValue]：偏好 `nightread_fill_level` 的存值，沿用三檔時代的 `l2`／`l3`（key 與預設值不變、不必遷移）。
 * - [fileKey]：新格式夜讀檔名的檔位段（`012.jpg.night.std.webp`／`012.jpg.night.more.webp`，見 [NightPages]）。
 *
 * 放在 app 而不是 domain：domain 模組不依賴 nightread 函式庫。
 */
enum class NightLevel(val tier: NightTier, val prefValue: String, val fileKey: String) {
    /** 標準（預設）：留白、封閉泡、簡單的大白塗黑。 */
    STANDARD(NightTier.L2, "l2", "std"),

    /** 更多：連較複雜的白也塗黑（有取捨的那一檔）。 */
    MORE(NightTier.L3, "l3", "more"),
    ;

    companion object {
        /** 產生時交給引擎的檔位（[NightLevel] 順序：標準在前＝一定交圖、當完成標記）。 */
        val engineTiers: List<NightTier> = entries.map { it.tier }

        /** 引擎交回的檔位 → 產品檔位；不是產品檔位（L1）→ null。 */
        fun ofTier(tier: NightTier): NightLevel? = entries.firstOrNull { it.tier == tier }

        /**
         * 偏好 `nightread_fill_level`（`TranslationPreferences.nightReadFillLevel`）→ 檔位。產品只寫 `l2`／`l3`；舊值照對應、
         * 不必遷移：`l1`（三檔時代的最低檔）與 `protect`（更早的「保護」）→ 標準，`aggressive` → 更多；其餘（含空字串、
         * 不認得的值）→ 標準。
         */
        fun fromPref(value: String?): NightLevel = when (value) {
            "l3", "aggressive" -> MORE
            else -> STANDARD
        }
    }
}
