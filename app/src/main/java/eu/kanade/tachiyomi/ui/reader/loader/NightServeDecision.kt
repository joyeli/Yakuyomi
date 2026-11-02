package eu.kanade.tachiyomi.ui.reader.loader

/**
 * 閱讀器每頁「送什麼、壓不壓暗」的決策（純函式；JVM 測試見 NightServeDecisionTest）。[NightPageStreams] 每次解碼都照這裡算，
 * 換檔位、選頁補換、章層級放行時的「現在該送什麼」也照這裡算（同一份規則，不各自猜）。
 *
 * **送什麼**（[NightPageStreams.Served]）：
 *  - [NightPageStreams.Served.HELD_ORIGINAL]：合併處理（翻譯＋夜讀一條線）時這頁已翻好、夜讀版還沒好——**白天與夜讀模式都**送
 *    `.yakuyomi` 子夾裡的原圖素材，畫面維持原圖，直到這頁兩樣都好（q3 WAIT）。條件全部成立才 hold：章層級 hold 生效、
 *    有這頁的 hold 記號、原圖素材在、這頁還沒有夜讀版（看標準檔＝完成標記）、這個 loader 沒放行過這頁（黏著放行）。
 *  - [NightPageStreams.Served.NIGHT]：有效夜讀模式而且這頁在目前檔位有夜讀檔。
 *  - [NightPageStreams.Served.PAGE]：頁檔本身（可能是原圖，也可能已是譯圖）。
 *
 * **壓暗**（q2 dim_all，只影響顯示、絕不寫檔）：有效夜讀模式、壓暗開關開、送的不是夜讀檔 → 亮度乘以
 * `1 − 壓暗值/100`（壓暗值夾到 0–90）；其餘 1（不壓暗）。
 *
 * **黏著放行**（[Result.markReleased]）：章層級 hold 沒生效（總開關關、夜讀段放棄、取消、做完收尾），但這頁仍有記號、
 * 有原圖、沒夜讀版——這時一定送頁檔（譯圖），loader 要把這頁記成「已放行」：之後夜讀恢復、章層級 hold 又生效，也不會把畫面上的
 * 譯圖換回原圖。
 */
internal object NightServeDecision {

    /** [kind]＝送哪個檔；[dim]＝顯示時的亮度倍率（1＝不壓暗）；[markReleased]＝loader 要把這頁記成已放行。 */
    data class Result(val kind: NightPageStreams.Served, val dim: Float, val markReleased: Boolean)

    /** 壓暗值的上限（變暗 %）：再暗就看不到東西了。 */
    const val MAX_DIM_PERCENT = 90

    /**
     * @param effectiveNight 有效夜讀模式（夜讀總開關 AND 閱讀器夜讀模式）。
     * @param holdActive 這章的章層級 hold 生效（`TranslationManager.isHoldActive`）。
     * @param hasHoldMarker `.yakuyomi` 子夾有這頁的 hold 記號（兩種任一）。
     * @param hasOriginal 這頁的原圖素材在（`OriginalMaterial.resolve` 不是 null）。
     * @param nightDone 這頁有夜讀版（`NightPages.resolveName(names, 頁, STANDARD) != null`）。
     * @param nightForTier 這頁在目前檔位有夜讀檔（`NightPages.resolveName(names, 頁, 目前檔位) != null`）。
     * @param stickyReleased 這個 loader 已放行過這頁。
     * @param dimEnabled 「沒有夜讀版的頁先壓暗」開著。
     * @param dimPercent 目前檔位的壓暗值（變暗 %）。
     */
    fun decide(
        effectiveNight: Boolean,
        holdActive: Boolean,
        hasHoldMarker: Boolean,
        hasOriginal: Boolean,
        nightDone: Boolean,
        nightForTier: Boolean,
        stickyReleased: Boolean,
        dimEnabled: Boolean,
        dimPercent: Int,
    ): Result {
        val held = holdActive && hasHoldMarker && hasOriginal && !nightDone && !stickyReleased
        val kind = when {
            held -> NightPageStreams.Served.HELD_ORIGINAL
            effectiveNight && nightForTier -> NightPageStreams.Served.NIGHT
            else -> NightPageStreams.Served.PAGE
        }
        return Result(
            kind = kind,
            dim = dimMultiplier(effectiveNight, dimEnabled, dimPercent, kind),
            markReleased = !holdActive && hasHoldMarker && hasOriginal && !nightDone,
        )
    }

    /**
     * 送 [kind] 時的亮度倍率（[decide] 的壓暗規則單獨拿出來：開檔失敗退回頁檔時、沒有 `nightStreams` 的 loader 都用它）。
     * 有效夜讀模式、壓暗開、送的不是夜讀檔 → `1 − 夾到 0–90 的壓暗值/100`；其餘 1。
     */
    fun dimMultiplier(
        effectiveNight: Boolean,
        dimEnabled: Boolean,
        dimPercent: Int,
        kind: NightPageStreams.Served,
    ): Float = if (effectiveNight && dimEnabled && kind != NightPageStreams.Served.NIGHT) {
        1f - dimPercent.coerceIn(0, MAX_DIM_PERCENT) / 100f
    } else {
        1f
    }
}
