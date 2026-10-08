package eu.kanade.presentation.reader.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SignalCellularAlt2Bar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.kanade.tachiyomi.data.nightread.NightAvailability
import eu.kanade.tachiyomi.data.nightread.NightLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * 閱讀器的夜讀懸浮控制（夜讀總開關開時常駐在畫面上層；總開關關時呼叫端根本不組合它）。
 *
 * - **只用圖示**，長按才出提示（TooltipBox，檔位槽唸「填黑：標準／更多」）。日常＝單顆 Ø36dp 圓鈕（`LightMode`）；
 *   夜讀＝約 121×36dp 膠囊：開關槽（`DarkMode`，primary 色）＋分隔線＋兩顆檔位槽（[NightLevel]：訊號 2 格＝標準、
 *   滿格＝更多，格越多塗越黑）。觸控範圍 48dp 高。
 * - **閒置變淡**（日常 0.35、夜讀 0.45），互動中與互動後 2.5 s 全亮，淡入淡出 200 ms；閱讀選單開著時淡出、不吃觸控
 *   （同頁碼與即時翻指示器的慣例，也免得疊到上下的 app bar）。e-ink 或 TalkBack／切換操作開著時固定全不透明、無動畫。
 * - **防誤觸**：閒置變淡時「夜讀 → 日常」要兩下（第一下只喚醒），見 [NightFabWake]。
 * - **可拖曳**，放開吸附左右邊（離邊 8dp、250 ms）；位置存 [position]（見 [NightFabPosition]）。夜讀展開時膠囊往畫面中央長，
 *   開關鈕永遠在拇指下。對控制元件範圍排除系統邊緣手勢（否則從邊緣往內拖會被當成「返回」）。
 * - 檔位不可用（[availability] 不是 READY）時檔位鈕灰（圖示 0.38），點了交給 [onTierUnavailable]（確認框／進度／不支援提示）；
 *   [availability] 為 null＝換章後還沒掃完，同樣畫灰（呼叫端點了不反應）。灰鈕的 TalkBack 狀態說明依原因（不支援／產生中／
 *   舊版單檔／尚未產生）。[progress] 非 null＝這章正在產生夜讀版，開關鈕外圈畫進度環（[Float.NaN]＝不定進度）；
 *   [generating]＝這章在夜讀佇列裡（排隊或產生中）。
 * - [noDifferenceHint] 每 +1 一次＝剛換的檔位對畫面上的頁沒有差異：短暫（約 1.5 s）提示，免得以為鈕壞了；TalkBack 另唸一次。
 * - 無障礙：開關 `toggleable(Role.Switch)`、檔位 `selectableGroup` + `selectable(Role.RadioButton)`；開關鈕帶「移到左側／右側」
 *   自訂動作，給沒辦法拖曳的使用者（掛在開關上：容器不是可聚焦節點，掛在那裡 TalkBack 叫不出來）。焦點順序：開關 → 標準 → 更多。
 * - 夜讀時若 App 是淺色主題，整顆鈕改用深色配色：黑頁上不會是一顆淺灰色的亮膠囊。
 */
@Composable
fun ReaderNightFloatingControl(
    menuVisible: Boolean,
    nightOn: Boolean,
    tier: NightLevel,
    availability: NightAvailability?,
    progress: Float?,
    generating: Boolean,
    noDifferenceHint: Int,
    position: String,
    onPositionChange: (String) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onTierSelect: (NightLevel) -> Unit,
    onTierUnavailable: () -> Unit,
    einkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val a11yNavigation = rememberAccessibilityNavigationActive()
    val alwaysOpaque = einkMode || a11yNavigation

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !menuVisible,
            enter = if (alwaysOpaque) EnterTransition.None else fadeIn(tween(FADE_MS)),
            exit = if (alwaysOpaque) ExitTransition.None else fadeOut(tween(FADE_MS)),
        ) {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
            val layout = NightFabLayout(
                containerW = constraints.maxWidth.toFloat(),
                containerH = constraints.maxHeight.toFloat(),
                insetLeft = insets.getLeft(density, layoutDirection).toFloat(),
                insetTop = insets.getTop(density).toFloat(),
                insetRight = insets.getRight(density, layoutDirection).toFloat(),
                insetBottom = insets.getBottom(density).toFloat(),
                density = density.density,
            )
            // 灰檔位鈕的 TalkBack 狀態說明：依實際原因（READY 時不用）
            val unavailableReason = when {
                availability == NightAvailability.READY -> null
                availability == NightAvailability.UNSUPPORTED -> MR.strings.nightread_needs_loose_download
                generating -> MR.strings.nightfab_tier_generating
                availability == NightAvailability.LEGACY -> MR.strings.nightfab_tier_legacy
                else -> MR.strings.nightfab_tier_not_generated
            }
            // 夜讀時淺色主題改深色配色（primary 取主題的 inversePrimary＝它在深色底上的對應色）
            val base = MaterialTheme.colorScheme
            val scheme = if (nightOn && base.surface.luminance() > 0.5f) {
                remember(base) { darkColorScheme(primary = base.inversePrimary) }
            } else {
                base
            }
            MaterialTheme(colorScheme = scheme) {
                NightFabBody(
                    layout = layout,
                    nightOn = nightOn,
                    tier = tier,
                    tiersEnabled = availability == NightAvailability.READY,
                    unavailableReason = unavailableReason?.let { stringResource(it) },
                    progress = progress,
                    noDifferenceHint = noDifferenceHint,
                    position = position,
                    onPositionChange = onPositionChange,
                    onNightModeChange = onNightModeChange,
                    onTierSelect = onTierSelect,
                    onTierUnavailable = onTierUnavailable,
                    alwaysOpaque = alwaysOpaque,
                )
            }
        }
    }
}

@Composable
private fun NightFabBody(
    layout: NightFabLayout,
    nightOn: Boolean,
    tier: NightLevel,
    tiersEnabled: Boolean,
    unavailableReason: String?,
    progress: Float?,
    noDifferenceHint: Int,
    position: String,
    onPositionChange: (String) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onTierSelect: (NightLevel) -> Unit,
    onTierUnavailable: () -> Unit,
    alwaysOpaque: Boolean,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 位置：本地狀態先更新（放開瞬間就到位、不等偏好寫回），偏好變了（別處改、或旋轉後重建）再同步回來。
    var pos by remember { mutableStateOf(NightFabPosition.parse(position)) }
    LaunchedEffect(position) { pos = NightFabPosition.parse(position) }

    // 閒置變淡：wake 記全亮到什麼時候（防誤觸的判斷用它），awake 驅動畫面（每次喚醒重開 2.5 s 計時）。
    val wake = remember { NightFabWake() }
    var wakeTick by remember { mutableIntStateOf(0) }
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(wakeTick) {
        if (wakeTick == 0) return@LaunchedEffect
        awake = true
        delay(NightFabWake.AWAKE_MS)
        awake = false
    }
    fun wakeUp() {
        wake.wake(SystemClock.uptimeMillis())
        wakeTick++
    }

    // 每顆鈕各自一個互動來源（共用的話按一顆、三顆一起出漣漪）；任一顆按著＝互動中、全亮
    val toggleInteraction = remember { MutableInteractionSource() }
    val tierInteractions = remember { NightLevel.entries.map { MutableInteractionSource() } }
    val togglePressed by toggleInteraction.collectIsPressedAsState()
    val tiersPressed = tierInteractions.map { it.collectIsPressedAsState().value }
    val pressed = togglePressed || tiersPressed.any { it }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val snapX = remember { Animatable(0f) }
    var snapping by remember { mutableStateOf(false) }

    val toggleTip = rememberTooltipState()
    val tierTips = NightLevel.entries.map { rememberTooltipState() }
    val hintTip = rememberTooltipState()
    val allTips = listOf(toggleTip) + tierTips
    val tipShowing = allTips.any { it.isVisible }
    LaunchedEffect(tipShowing) { if (tipShowing) wakeUp() }

    // 換了檔位、畫面上的頁卻沒變：短暫提示（非持續型 tooltip，約 1.5 s 自己收；PlainTooltip 是彈窗、TalkBack 不會唸，
    // 另外 announce 一次）。記下進來時的值：選單開關造成重新組合時不重播舊提示。
    val view = LocalView.current
    val hintText = stringResource(MR.strings.nightfab_no_difference)
    var shownHint by remember { mutableIntStateOf(noDifferenceHint) }
    LaunchedEffect(noDifferenceHint) {
        if (noDifferenceHint > shownHint) {
            shownHint = noDifferenceHint
            wakeUp()
            @Suppress("DEPRECATION") // API 36 標棄用、仍有作用；Compose 沒有一次性朗讀的替代
            view.announceForAccessibility(hintText)
            hintTip.show()
        }
    }

    // 透明度與位置只在繪製／擺放階段讀（淡入淡出、吸附動畫、拖曳每一格都不重組整顆鈕）
    val full = alwaysOpaque || pressed || dragging || awake || tipShowing
    val alphaState = animateFloatAsState(
        targetValue = if (full) 1f else NightFabWake.idleAlpha(nightOn),
        animationSpec = if (alwaysOpaque) snap() else tween(FADE_MS),
        label = "nightFabAlpha",
    )

    val boxW = layout.boxWidth(nightOn)

    // 拖曳回呼與擺放 lambda 裡要讀最新值（pointerInput 的區塊不會因重組而重建）
    val currentLayout by rememberUpdatedState(layout)
    val currentNight by rememberUpdatedState(nightOn)
    val currentPos by rememberUpdatedState(pos)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentAlwaysOpaque by rememberUpdatedState(alwaysOpaque)

    /** 目前的左緣／上緣（px）：拖曳中＝手指位置、吸附中＝動畫值、其餘＝停靠位置。讀的都是狀態，擺放階段呼叫。 */
    fun currentX(): Float = when {
        dragging -> dragX
        snapping -> snapX.value
        else -> currentLayout.left(currentPos.side, currentNight)
    }
    fun currentY(): Float = if (dragging) dragY else currentLayout.top(currentPos)

    fun moveTo(side: NightFabPosition.Side, yFrac: Float?) {
        val newPos = NightFabPosition(side, yFrac)
        pos = newPos
        currentOnPositionChange(newPos.encode())
    }

    fun finishDrag() {
        if (!dragging) return
        val l = currentLayout
        val night = currentNight
        val side = l.snapSide(dragX, night)
        val frac = l.fracOf(dragY)
        val from = dragX
        moveTo(side, frac)
        // 先把動畫起點設在放開的位置再切換狀態，免得有一格畫在舊位置
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            snapX.snapTo(from)
            snapping = true
            dragging = false
            snapX.animateTo(
                l.left(side, night),
                if (currentAlwaysOpaque) snap() else tween(SNAP_MS),
            )
            snapping = false
        }
        wakeUp()
    }

    val modeDesc = stringResource(MR.strings.nightfab_mode)
    val stateOn = stringResource(MR.strings.nightfab_state_on)
    val stateOff = stringResource(MR.strings.nightfab_state_off)
    val moveLeft = stringResource(MR.strings.nightfab_move_left)
    val moveRight = stringResource(MR.strings.nightfab_move_right)
    val toggleTipText = stringResource(if (nightOn) MR.strings.nightfab_to_day else MR.strings.nightfab_to_night)
    val tierLabels = NightLevel.entries.associateWith { stringResource(tierLabelRes(it)) }
    val moveActions = listOf(
        CustomAccessibilityAction(moveLeft) {
            moveTo(NightFabPosition.Side.LEFT, currentPos.yFrac)
            true
        },
        CustomAccessibilityAction(moveRight) {
            moveTo(NightFabPosition.Side.RIGHT, currentPos.yFrac)
            true
        },
    )

    val positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above)
    val shape = if (nightOn) RoundedCornerShape(VISUAL_RADIUS) else CircleShape
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (alwaysOpaque) 1f else 0.85f)

    // 位移放在提示框的外層：提示框以外層的位置當錨點，放在裡面會讓「沒有差異」提示跑到畫面左上角
    TooltipBox(
        positionProvider = positionProvider,
        tooltip = { PlainTooltip { Text(hintText) } },
        state = hintTip,
        modifier = Modifier.offset { IntOffset(currentX().roundToInt(), currentY().roundToInt()) },
        focusable = false,
        enableUserInput = false,
    ) {
        Box(
            modifier = Modifier
                .systemGestureExclusion()
                .size(
                    width = with(density) { boxW.toDp() },
                    height = with(density) { layout.boxHeight.toDp() },
                )
                .graphicsLayer { this.alpha = if (currentAlwaysOpaque) 1f else alphaState.value }
                .semantics { isTraversalGroup = true }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            allTips.forEach { it.dismiss() }
                            dragX = currentX()
                            dragY = currentY()
                            dragging = true
                        },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                        onDrag = { change, delta ->
                            change.consume()
                            dragX = currentLayout.clampLeft(dragX + delta.x, currentNight)
                            dragY = currentLayout.clampTop(dragY + delta.y)
                        },
                    )
                },
        ) {
            // 底：日常＝Ø36 圓、夜讀＝高 36 的膠囊（觸控框上下各多 6dp 透明區，日常左右也多 6dp）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 6.dp, horizontal = if (nightOn) 0.dp else 6.dp)
                    .shadow(elevation = 3.dp, shape = shape)
                    .background(bg, shape),
            )
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = if (nightOn) 2.dp else 0.dp)
                    .semantics { isTraversalGroup = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val toggle = @Composable {
                    TooltipBox(
                        positionProvider = positionProvider,
                        tooltip = { PlainTooltip { Text(toggleTipText) } },
                        state = toggleTip,
                        // 焦點順序：開關永遠先（貼右邊時它排在兩個檔位後面）
                        modifier = Modifier.semantics { traversalIndex = -1f },
                        focusable = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = if (nightOn) 44.dp else 48.dp, height = 48.dp)
                                .toggleable(
                                    value = nightOn,
                                    interactionSource = toggleInteraction,
                                    indication = ripple(bounded = false, radius = 20.dp),
                                    role = Role.Switch,
                                    onValueChange = {
                                        val action = wake.onToggleTap(SystemClock.uptimeMillis(), nightOn, alwaysOpaque)
                                        wakeTick++
                                        if (action == NightFabWake.ToggleAction.TOGGLE) onNightModeChange(!nightOn)
                                    },
                                )
                                .semantics {
                                    contentDescription = modeDesc
                                    stateDescription = if (nightOn) stateOn else stateOff
                                    // 「移到左側／右側」掛在這裡：開關會被 TooltipBox 合併成可聚焦節點，TalkBack 叫得出動作選單
                                    customActions = moveActions
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (progress != null) {
                                if (progress.isNaN()) {
                                    CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 2.dp)
                                } else {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(34.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (nightOn) Icons.Filled.DarkMode else Icons.Outlined.LightMode,
                                contentDescription = null,
                                tint = if (nightOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                val tiers = @Composable {
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 16.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                    Row(modifier = Modifier.selectableGroup()) {
                        NightLevel.entries.forEachIndexed { i, t ->
                            val label = tierLabels.getValue(t)
                            val selected = t == tier
                            TooltipBox(
                                positionProvider = positionProvider,
                                tooltip = { PlainTooltip { Text(label) } },
                                state = tierTips[i],
                                focusable = false,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 36.dp, height = 48.dp)
                                        // 不可用也照樣可點（點了跳確認框／報進度），所以不設 enabled=false，改用狀態說明
                                        .selectable(
                                            selected = selected && tiersEnabled,
                                            interactionSource = tierInteractions[i],
                                            indication = ripple(bounded = false, radius = 16.dp),
                                            role = Role.RadioButton,
                                            onClick = {
                                                wake.onTierTap(SystemClock.uptimeMillis())
                                                wakeTick++
                                                when {
                                                    !tiersEnabled -> onTierUnavailable()
                                                    t != tier -> onTierSelect(t)
                                                }
                                            },
                                        )
                                        .semantics {
                                            contentDescription = label
                                            if (!tiersEnabled && unavailableReason != null) {
                                                stateDescription = unavailableReason
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected && tiersEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                        )
                                    }
                                    Icon(
                                        imageVector = tierIcon(t),
                                        contentDescription = null,
                                        tint = if (selected && tiersEnabled) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier
                                            .size(18.dp)
                                            .alpha(if (tiersEnabled) 1f else DISABLED_ICON_ALPHA),
                                    )
                                }
                            }
                        }
                    }
                }
                // 開關鈕永遠在貼邊那一側（拇指下），膠囊往畫面中央長
                if (!nightOn) {
                    toggle()
                } else if (pos.side == NightFabPosition.Side.RIGHT) {
                    tiers()
                    toggle()
                } else {
                    toggle()
                    tiers()
                }
            }
        }
    }
}

/** 訊號格數＝塗黑多寡：標準 2 格、更多滿格（三檔時代的 1 格＝L1 已從產品拿掉）。 */
private fun tierIcon(level: NightLevel): ImageVector = when (level) {
    NightLevel.STANDARD -> Icons.Outlined.SignalCellularAlt2Bar
    NightLevel.MORE -> Icons.Outlined.SignalCellularAlt
}

private fun tierLabelRes(level: NightLevel) = when (level) {
    NightLevel.STANDARD -> MR.strings.nightfab_level_standard
    NightLevel.MORE -> MR.strings.nightfab_level_more
}

/**
 * TalkBack（觸控探索）或切換操作開著：懸浮鈕固定全不透明（閒置 0.35 的對比不到 3:1）、沒有兩下規則。
 * 跟著系統設定即時更新：無障礙整體開關、觸控探索，以及 API 33+ 的「已啟用服務變了」（已開著別的服務時再開切換操作，
 * 整體開關本來就是開的、前兩個回呼都不會來）；API 33 以下回到前景（ON_RESUME）時重判一次補這個洞。
 */
@Composable
private fun rememberAccessibilityNavigationActive(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService<AccessibilityManager>() }
    var active by remember(manager) { mutableStateOf(manager.isNavigationAssistActive()) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener {
            active = manager.isNavigationAssistActive()
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener {
            active = manager.isNavigationAssistActive()
        }
        manager.addAccessibilityStateChangeListener(stateListener)
        manager.addTouchExplorationStateChangeListener(touchListener)
        val servicesListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AccessibilityManager.AccessibilityServicesStateChangeListener {
                active = manager.isNavigationAssistActive()
            }.also { manager.addAccessibilityServicesStateChangeListener(it) }
        } else {
            null
        }
        onDispose {
            manager.removeAccessibilityStateChangeListener(stateListener)
            manager.removeTouchExplorationStateChangeListener(touchListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && servicesListener != null) {
                manager.removeAccessibilityServicesStateChangeListener(servicesListener)
            }
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LifecycleResumeEffect(manager) {
            active = manager.isNavigationAssistActive()
            onPauseOrDispose {}
        }
    }
    return active
}

private fun AccessibilityManager?.isNavigationAssistActive(): Boolean {
    if (this == null || !isEnabled) return false
    if (isTouchExplorationEnabled) return true
    // 切換操作不開觸控探索：看已啟用的服務裡有沒有它
    return runCatching {
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id?.contains("switchaccess", ignoreCase = true) == true }
    }.getOrDefault(false)
}

/** 淡入淡出。 */
private const val FADE_MS = 200

/** 放開後吸附到邊。 */
private const val SNAP_MS = 250

/** Material 停用圖示透明度。 */
private const val DISABLED_ICON_ALPHA = 0.38f

private val VISUAL_RADIUS = 18.dp
