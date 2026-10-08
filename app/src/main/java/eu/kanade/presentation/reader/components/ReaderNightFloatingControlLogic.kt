package eu.kanade.presentation.reader.components

import java.util.Locale
import kotlin.math.max

// 夜讀懸浮鈕的純邏輯（不碰 Compose／Android，JVM 單元測試見 NightFloatingControlLogicTest）：位置的存取與換算、
// 閒置變淡與防誤觸。畫面本體在 ReaderNightFloatingControl。

/**
 * 懸浮鈕位置（偏好 `ReaderPreferences.nightFabPosition`）：[side]＝貼左或右邊（物理左右、不隨閱讀方向翻轉），
 * [yFrac]＝可移動範圍內的垂直比例（0＝最上、1＝最下）。使用者拖曳前 [yFrac] 為 null，用「距底 96dp」規則。
 * 存成 `R`、`L:0.4000` 這種字串；用比例存，旋轉螢幕或折疊機展開後位置仍合理。
 */
internal data class NightFabPosition(val side: Side, val yFrac: Float?) {

    enum class Side { LEFT, RIGHT }

    fun encode(): String {
        val s = if (side == Side.LEFT) "L" else "R"
        return if (yFrac == null) s else "$s:${"%.4f".format(Locale.ROOT, yFrac)}"
    }

    companion object {
        val DEFAULT = NightFabPosition(Side.RIGHT, null)

        /** 解析偏好字串；壞值一律退回 [DEFAULT] 的對應部分（side 不認得＝右邊，比例不認得＝null）。 */
        fun parse(raw: String?): NightFabPosition {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.trim().split(':', limit = 2)
            val side = if (parts[0].equals("L", ignoreCase = true)) Side.LEFT else Side.RIGHT
            val frac = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            return NightFabPosition(side, frac)
        }
    }
}

/**
 * 懸浮鈕的版面換算（全部 px；[density]＝每 dp 幾 px）。容器＝整個閱讀畫面，inset＝系統列與瀏海（不管目前有沒有顯示，
 * 免得系統列滑出來時蓋住鈕）。
 *
 * 尺寸：觸控框高 48dp、視覺高 36dp（上下各多 6dp 透明觸控區）；日常是 48×48 觸控框裡的 Ø36 圓鈕（左右也多 6dp），
 * 夜讀是約 121×36 的膠囊（兩顆檔位槽；左右沒有多出來的觸控區）。「離邊 8dp」量的是**視覺**邊。
 */
internal class NightFabLayout(
    private val containerW: Float,
    private val containerH: Float,
    private val insetLeft: Float,
    private val insetTop: Float,
    private val insetRight: Float,
    private val insetBottom: Float,
    private val density: Float,
) {
    private fun dp(v: Float) = v * density

    /** 觸控框高。 */
    val boxHeight: Float get() = dp(TOUCH_HEIGHT_DP)

    /** 觸控框寬（日常＝單顆圓鈕、夜讀＝展開的膠囊）。 */
    fun boxWidth(night: Boolean): Float = dp(if (night) NIGHT_WIDTH_DP else DAY_WIDTH_DP)

    /** 觸控框比視覺多出來的左右邊。 */
    private fun padX(night: Boolean): Float = if (night) 0f else dp((DAY_WIDTH_DP - VISUAL_HEIGHT_DP) / 2)

    private val padY: Float get() = dp((TOUCH_HEIGHT_DP - VISUAL_HEIGHT_DP) / 2)

    /** 觸控框上緣的可移動範圍：視覺邊離安全區上下各 [MARGIN_DP]。容器太矮時兩端重合。 */
    val minTop: Float get() = insetTop + dp(MARGIN_DP) - padY
    val maxTop: Float get() = max(minTop, containerH - insetBottom - dp(MARGIN_DP) + padY - boxHeight)

    /** 使用者還沒拖過：視覺底邊離安全區底 [DEFAULT_BOTTOM_DP]（夾在可移動範圍內）。 */
    val defaultTop: Float
        get() = (containerH - insetBottom - dp(DEFAULT_BOTTOM_DP) - dp(VISUAL_HEIGHT_DP) - padY)
            .coerceIn(minTop, maxTop)

    /** 貼在 [side] 時觸控框的左緣：視覺邊離安全區 [MARGIN_DP]。 */
    fun left(side: NightFabPosition.Side, night: Boolean): Float = when (side) {
        NightFabPosition.Side.LEFT -> insetLeft + dp(MARGIN_DP) - padX(night)
        NightFabPosition.Side.RIGHT -> containerW - insetRight - dp(MARGIN_DP) + padX(night) - boxWidth(night)
    }

    /** [pos] 的觸控框上緣。 */
    fun top(pos: NightFabPosition): Float =
        pos.yFrac?.let { minTop + it.coerceIn(0f, 1f) * (maxTop - minTop) } ?: defaultTop

    /** 觸控框上緣 → 存檔用的比例（夾在 0..1）。 */
    fun fracOf(top: Float): Float = if (maxTop <= minTop) 0f else ((top - minTop) / (maxTop - minTop)).coerceIn(0f, 1f)

    /** 拖曳中的位置夾在畫面內。 */
    fun clampLeft(left: Float, night: Boolean): Float = left.coerceIn(0f, max(0f, containerW - boxWidth(night)))
    fun clampTop(top: Float): Float = top.coerceIn(minTop, maxTop)

    /** 放開時吸附到哪一邊：觸控框中心在畫面左半＝左邊。 */
    fun snapSide(left: Float, night: Boolean): NightFabPosition.Side =
        if (left + boxWidth(night) / 2 < containerW / 2) NightFabPosition.Side.LEFT else NightFabPosition.Side.RIGHT

    companion object {
        const val MARGIN_DP = 8f
        const val TOUCH_HEIGHT_DP = 48f
        const val VISUAL_HEIGHT_DP = 36f
        const val DAY_WIDTH_DP = 48f

        /** 檔位槽數＝產品檔位數（`NightLevel.entries.size`，標準／更多；NightFloatingControlLogicTest 守兩者一致）。 */
        const val TIER_SLOTS = 2

        /** 夜讀膠囊：左右各 2dp ＋ 開關槽 44 ＋ 分隔線 1 ＋ [TIER_SLOTS] 個檔位槽各 36。 */
        const val NIGHT_WIDTH_DP = 2f + 44f + 1f + TIER_SLOTS * 36f + 2f
        const val DEFAULT_BOTTOM_DP = 96f
    }
}

/**
 * 閒置變淡與防誤觸（時間由呼叫端給，測試可注入）。
 *
 * - 互動（點、拖、長按出提示）後 [AWAKE_MS] 內全亮；之後淡到 [idleAlpha]（日常 0.35、夜讀 0.45）。翻頁不喚醒。
 * - **防誤觸**：閒置變淡時，「夜讀 → 日常」要兩下——第一下只喚醒（全亮 2.5 s），第二下才切換。夜裡誤點一下就整頁
 *   亮白太刺眼；日常 → 夜讀、切檔位不受此限。
 * - [onToggleTap] 的 alwaysOpaque（e-ink、TalkBack／切換操作）：固定全不透明、沒有「變淡」，也就沒有兩下規則。
 */
internal class NightFabWake(private val awakeMs: Long = AWAKE_MS) {

    enum class ToggleAction {
        /** 切換日常／夜讀。 */
        TOGGLE,

        /** 只喚醒（閒置變淡時的夜讀 → 日常第一下）。 */
        WAKE_ONLY,
    }

    private var awakeUntil = Long.MIN_VALUE

    /** 現在是否在互動後的全亮期間。 */
    fun isAwake(now: Long): Boolean = now < awakeUntil

    /** 是否處於閒置變淡狀態。 */
    fun isFaded(now: Long, alwaysOpaque: Boolean): Boolean = !alwaysOpaque && !isAwake(now)

    /** 喚醒（全亮 [awakeMs]）：拖曳結束、長按出提示、點任何鈕都會呼叫。 */
    fun wake(now: Long) {
        awakeUntil = now + awakeMs
    }

    /** 點了開關鈕：決定要切換還是只喚醒，並喚醒。 */
    fun onToggleTap(now: Long, nightOn: Boolean, alwaysOpaque: Boolean): ToggleAction {
        val faded = isFaded(now, alwaysOpaque)
        wake(now)
        return if (nightOn && faded) ToggleAction.WAKE_ONLY else ToggleAction.TOGGLE
    }

    /** 點了檔位鈕：一律照常動作（只喚醒、不擋）。 */
    fun onTierTap(now: Long) = wake(now)

    companion object {
        const val AWAKE_MS = 2_500L

        /** 閒置透明度：日常 0.35、夜讀 0.45（夜讀時鈕也是找回日常的入口，稍微明顯一點）。 */
        fun idleAlpha(nightOn: Boolean): Float = if (nightOn) 0.45f else 0.35f
    }
}
