package dev.kuml.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

internal const val SPLIT_MIN_FRACTION: Double = 0.15
internal val SPLIT_MIN_PANE_DP = 200.dp
internal const val SPLIT_DEFAULT_FRACTION: Double = 0.5
internal val SPLIT_DIVIDER_WIDTH = 8.dp
internal const val SPLIT_STEP = 0.02
internal const val SPLIT_STEP_LARGE = 0.10
internal const val SPLIT_DIVIDER_TEST_TAG = "kuml-split-divider"
private const val DOUBLE_CLICK_MS = 400L

internal const val SPLIT_MAX_FRACTION: Double = 1.0 - SPLIT_MIN_FRACTION
private const val SPLIT_EPSILON = 1e-9

/**
 * Raw lower/upper share. The lower bound is the larger of [minFraction] and the share that keeps
 * [startMinPx] pixels for the start pane; the upper bound is the smaller of [maxFraction] and the
 * share that keeps [endMinPx] pixels for the end pane. A non-positive or infinite [totalPx]
 * contributes no pixel constraint (used when loading settings, no layout yet). Returns null when
 * the bounds cannot be met at the same time.
 */
private fun rawBounds(
    totalPx: Float,
    startMinPx: Float,
    endMinPx: Float,
    minFraction: Double,
    maxFraction: Double,
): ClosedFloatingPointRange<Double>? {
    val known = totalPx > 0f && totalPx.isFinite()
    val lo = maxOf(minFraction, if (known) (startMinPx / totalPx).toDouble() else 0.0)
    val hi = minOf(maxFraction, if (known) 1.0 - (endMinPx / totalPx).toDouble() else 1.0)
    if (lo > hi + SPLIT_EPSILON) return null
    return lo..maxOf(lo, hi)
}

/**
 * Allowed range of the start-pane share: [minPanePx] pixels for the start pane, [endMinPx] for
 * the end pane, [minFraction]..[maxFraction] as share limits. If they cannot be met at once the
 * range collapses to [fallback].
 */
internal fun splitBounds(
    totalPx: Float,
    minPanePx: Float,
    endMinPx: Float = minPanePx,
    minFraction: Double = SPLIT_MIN_FRACTION,
    maxFraction: Double = SPLIT_MAX_FRACTION,
    fallback: Double = SPLIT_DEFAULT_FRACTION,
): ClosedFloatingPointRange<Double> =
    rawBounds(
        totalPx = totalPx,
        startMinPx = minPanePx,
        endMinPx = endMinPx,
        minFraction = minFraction,
        maxFraction = maxFraction,
    ) ?: (fallback..fallback)

/** Clamps [fraction] into [splitBounds]; NaN/infinite input yields [fallback]. Never divides by zero. */
internal fun clampSplitFraction(
    fraction: Double,
    totalPx: Float,
    minPanePx: Float,
    minFraction: Double = SPLIT_MIN_FRACTION,
    endMinPx: Float = minPanePx,
    maxFraction: Double = SPLIT_MAX_FRACTION,
    fallback: Double = SPLIT_DEFAULT_FRACTION,
): Double {
    if (fraction.isNaN() || fraction.isInfinite()) return fallback
    val range =
        rawBounds(
            totalPx = totalPx,
            startMinPx = minPanePx,
            endMinPx = endMinPx,
            minFraction = minFraction,
            maxFraction = maxFraction,
        ) ?: return fallback
    return fraction.coerceIn(range.start, range.endInclusive)
}

/** Absolute pointer position to share: the divider ends up centred under the pointer. */
internal fun fractionFromPointer(
    pointerXInRow: Float,
    rowWidthPx: Float,
    dividerPx: Float,
): Double {
    val usable = rowWidthPx - dividerPx
    if (usable <= 0f) return SPLIT_DEFAULT_FRACTION
    return ((pointerXInRow - dividerPx / 2f) / usable).toDouble()
}

/**
 * Two panes side by side with a draggable divider (8 dp of real layout space, never an overlay,
 * because both neighbours in the editor/preview case are heavyweight SwingPanels).
 *
 * [fraction] is only the *wish*; the layout uses a clamped effective value (min 15 % and 200 dp
 * per pane, both overridable) without writing it back, so shrinking and re-growing the window restores the wish.
 * [onFractionChange] fires continuously while dragging, [onFractionCommit] exactly once per
 * gesture (release / cancel / key press), and is the place to persist. Both callbacks only set
 * Compose state and may be invoked from the AWT event thread.
 */
@Composable
fun ResizableSplit(
    fraction: Double,
    onFractionChange: (Double) -> Unit,
    onFractionCommit: (Double) -> Unit,
    dividerDescription: String,
    modifier: Modifier = Modifier,
    defaultFraction: Double = SPLIT_DEFAULT_FRACTION,
    startMinPane: Dp = SPLIT_MIN_PANE_DP,
    endMinPane: Dp = SPLIT_MIN_PANE_DP,
    maxFraction: Double = SPLIT_MAX_FRACTION,
    testTag: String = SPLIT_DIVIDER_TEST_TAG,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var rowOriginX by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier = modifier) {
        val totalPx = constraints.maxWidth.toFloat()
        val dividerPx = with(density) { SPLIT_DIVIDER_WIDTH.toPx() }
        val minPanePx = with(density) { startMinPane.toPx() }
        val endMinPx = with(density) { endMinPane.toPx() }
        val usable = (totalPx - dividerPx).coerceAtLeast(0f)
        val effective =
            clampSplitFraction(
                fraction = fraction,
                totalPx = usable,
                minPanePx = minPanePx,
                endMinPx = endMinPx,
                maxFraction = maxFraction,
                fallback = defaultFraction,
            )
        val bounds =
            splitBounds(
                totalPx = usable,
                minPanePx = minPanePx,
                endMinPx = endMinPx,
                maxFraction = maxFraction,
                fallback = defaultFraction,
            )
        Row(
            modifier =
                Modifier.fillMaxSize().onGloballyPositioned { rowOriginX = it.positionInWindow().x },
        ) {
            Box(modifier = Modifier.width(with(density) { (usable * effective).toFloat().toDp() }).fillMaxHeight()) {
                start()
            }
            SplitDivider(
                effective = effective,
                bounds = bounds,
                dividerDescription = dividerDescription,
                defaultFraction = defaultFraction,
                testTag = testTag,
                pointerToFraction = { windowX ->
                    clampSplitFraction(
                        fraction = fractionFromPointer(pointerXInRow = windowX - rowOriginX, rowWidthPx = totalPx, dividerPx = dividerPx),
                        totalPx = usable,
                        minPanePx = minPanePx,
                        endMinPx = endMinPx,
                        maxFraction = maxFraction,
                        fallback = defaultFraction,
                    )
                },
                onFractionChange = onFractionChange,
                onFractionCommit = onFractionCommit,
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { end() }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun SplitDivider(
    effective: Double,
    bounds: ClosedFloatingPointRange<Double>,
    dividerDescription: String,
    defaultFraction: Double,
    testTag: String,
    pointerToFraction: (Float) -> Double,
    onFractionChange: (Double) -> Unit,
    onFractionCommit: (Double) -> Unit,
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    var hoverVisible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dividerOriginX by remember { mutableStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val currentToFraction by rememberUpdatedState(pointerToFraction)
    val currentChange by rememberUpdatedState(onFractionChange)
    val currentCommit by rememberUpdatedState(onFractionCommit)
    val currentEffective by rememberUpdatedState(effective)
    val currentBounds by rememberUpdatedState(bounds)
    // Screen origin (AWT points) of the window content, captured at drag start; lets the AWT
    // safety net convert screen coordinates back to window pixels without a Window reference.
    var screenOriginPt by remember { mutableStateOf(0.0) }
    var finished by remember { mutableStateOf(true) }
    var lastValue by remember { mutableStateOf(effective) }

    fun finishDrag() {
        if (finished) return
        finished = true
        dragging = false
        currentCommit(lastValue)
    }

    fun update(value: Double) {
        lastValue = value
        currentChange(value)
    }

    LaunchedEffect(hovered) {
        if (hovered) delay(100)
        hoverVisible = hovered
    }

    // Safety net: heavyweight SwingPanels next to the divider can swallow the release/drag events
    // Compose would otherwise see. Always unregistered on dispose; headless toolkits are tolerated.
    DisposableEffect(dragging) {
        if (!dragging) return@DisposableEffect onDispose {}
        val listener =
            AWTEventListener { event ->
                when (event) {
                    is MouseEvent ->
                        when (event.id) {
                            MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_MOVED -> {
                                val windowX = ((event.locationOnScreen.x - screenOriginPt) * density.density).toFloat()
                                update(currentToFraction(windowX))
                            }
                            MouseEvent.MOUSE_RELEASED -> finishDrag()
                        }
                    is WindowEvent -> if (event.id == WindowEvent.WINDOW_LOST_FOCUS) finishDrag()
                }
            }
        val mask = AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK
        val registered = runCatching { Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask) }.isSuccess
        onDispose {
            if (registered) runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
        }
    }

    fun commitKey(value: Double) {
        val clamped = value.coerceIn(currentBounds.start, currentBounds.endInclusive)
        lastValue = clamped
        currentChange(clamped)
        currentCommit(clamped)
    }

    // Reset stores the wish (the default), not the clamped value: a narrow window must not
    // overwrite the saved share with whatever the current bounds allow.
    fun resetToDefault() {
        lastValue = defaultFraction
        currentChange(defaultFraction)
        currentCommit(defaultFraction)
    }

    val active = dragging || hoverVisible
    val lineColor =
        when {
            dragging -> MaterialTheme.colorScheme.primary
            hoverVisible -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    Box(
        modifier =
            Modifier
                .width(SPLIT_DIVIDER_WIDTH)
                .fillMaxHeight()
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                ).onGloballyPositioned { dividerOriginX = it.positionInWindow().x }
                .testTag(testTag)
                .semantics(mergeDescendants = true) {
                    contentDescription = dividerDescription
                    stateDescription = "${(currentEffective * 100).roundToInt()} %"
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            currentEffective.toFloat(),
                            currentBounds.start.toFloat()..currentBounds.endInclusive.toFloat(),
                        )
                    setProgress { v ->
                        commitKey(v.toDouble())
                        true
                    }
                }.hoverable(interaction)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .focusRequester(focusRequester)
                .focusable(interactionSource = interaction)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = if (e.isShiftPressed) SPLIT_STEP_LARGE else SPLIT_STEP
                    when (e.key) {
                        Key.DirectionLeft -> commitKey(currentEffective - step)
                        Key.DirectionRight -> commitKey(currentEffective + step)
                        Key.MoveHome -> commitKey(currentBounds.start)
                        Key.MoveEnd -> commitKey(currentBounds.endInclusive)
                        Key.Enter, Key.NumPadEnter -> resetToDefault()
                        else -> return@onKeyEvent false
                    }
                    true
                }.pointerInput(Unit) {
                    // Never consumes: a consumed down would cancel the drag gesture below.
                    var lastDown = -1L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        focusRequester.requestFocus()
                        if (lastDown >= 0 && down.uptimeMillis - lastDown <= DOUBLE_CLICK_MS) {
                            lastDown = -1L
                            resetToDefault()
                        } else {
                            lastDown = down.uptimeMillis
                        }
                    }
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { local ->
                            val windowX = dividerOriginX + local.x
                            val mouse =
                                runCatching {
                                    MouseInfo
                                        .getPointerInfo()
                                        ?.location
                                        ?.x
                                        ?.toDouble()
                                }.getOrNull()
                            screenOriginPt = if (mouse != null) mouse - windowX / density.density else 0.0
                            finished = false
                            lastValue = currentEffective
                            dragging = true
                        },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                        onDrag = { change, _: Offset ->
                            change.consume()
                            update(currentToFraction(dividerOriginX + change.position.x))
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(if (active) 2.dp else 1.dp)
                    .fillMaxHeight()
                    .background(lineColor),
        )
    }
}
