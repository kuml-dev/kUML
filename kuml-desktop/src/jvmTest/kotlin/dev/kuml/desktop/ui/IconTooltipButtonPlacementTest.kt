package dev.kuml.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Compose popups are NOT drawn over a heavyweight `SwingPanel` (the editor and the Batik preview), so
 * a tooltip that overlaps one simply vanishes. The "heavyweight" tests below model that panel as a
 * tagged [Box] next to the bar and assert the tooltip never touches it.
 *
 * Original regression test for the status-bar view-mode buttons: the "Source" tooltip covered its own
 * button because [tooltipAbove] passed `alignment = BottomCenter`, which places the popup BELOW the
 * anchor (= the button's top edge). Measured in a headless Compose scene with the button flush
 * against the window edge it must tip towards — exactly the situation of the status bar.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class IconTooltipButtonPlacementTest :
    FunSpec({
        data class Bounds(
            val button: DpRect,
            val tooltip: DpRect,
        )

        fun measure(
            buttonAtBottom: Boolean,
            placement: TooltipPlacement,
        ): Bounds {
            lateinit var result: Bounds
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (buttonAtBottom) Spacer(modifier = Modifier.weight(1f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                IconTooltipButton(
                                    icon = KumlIcons.ViewSource,
                                    description = "Source",
                                    onClick = {},
                                    tooltipPlacement = placement,
                                )
                            }
                        }
                    }
                }
                onNodeWithContentDescription("Source").performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(1_000) // past TooltipArea's 500 ms delay
                waitForIdle()
                result =
                    Bounds(
                        button = onNodeWithContentDescription("Source").getBoundsInRoot(),
                        tooltip = onNodeWithText("Source").getBoundsInRoot(),
                    )
            }
            return result
        }

        test("tooltipAbove: tooltip sits entirely above a button at the bottom edge of the window") {
            val (button, tooltip) = measure(buttonAtBottom = true, placement = tooltipAbove())
            tooltip.bottom.value shouldBeLessThanOrEqual button.top.value
        }

        test("tooltipAbove: tooltip is horizontally centred over the button") {
            val (button, tooltip) = measure(buttonAtBottom = true, placement = tooltipAbove())
            val offCentre = ((tooltip.left.value + tooltip.right.value) - (button.left.value + button.right.value)) / 2f
            kotlin.math.abs(offCentre) shouldBeLessThanOrEqual 1f
        }

        test("tooltipBelow: tooltip sits entirely below a button at the top edge of the window") {
            val (button, tooltip) = measure(buttonAtBottom = false, placement = tooltipBelow())
            tooltip.top.value shouldBeGreaterThanOrEqual button.bottom.value
        }
        // ── Bars next to a heavyweight SwingPanel (zoom / simulation / find bar, status bar) ──

        data class BarBounds(
            val button: DpRect,
            val tooltip: DpRect,
            val heavyweight: DpRect,
            val bar: DpRect,
        )

        fun DpRect.intersects(other: DpRect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

        fun measureBar(
            barAtTop: Boolean,
            placement: TooltipPlacement,
        ): BarBounds {
            lateinit var result: BarBounds
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val bar: @androidx.compose.runtime.Composable () -> Unit = {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(36.dp).testTag("bar"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (barAtTop) Arrangement.Start else Arrangement.End,
                                ) {
                                    IconTooltipButton(
                                        icon = KumlIcons.ViewSource,
                                        description = "Source",
                                        onClick = {},
                                        tooltipPlacement = placement,
                                    )
                                }
                            }
                            val heavyweight: @androidx.compose.runtime.Composable (Modifier) -> Unit = { m ->
                                Box(modifier = m.testTag("heavyweight"))
                            }
                            if (barAtTop) {
                                bar()
                                heavyweight(Modifier.weight(1f).fillMaxWidth())
                            } else {
                                heavyweight(Modifier.weight(1f).fillMaxWidth())
                                bar()
                            }
                        }
                    }
                }
                onNodeWithContentDescription("Source").performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                result =
                    BarBounds(
                        button = onNodeWithContentDescription("Source").getBoundsInRoot(),
                        tooltip = onNodeWithText("Source").getBoundsInRoot(),
                        heavyweight = onNodeWithTag("heavyweight").getBoundsInRoot(),
                        bar = onNodeWithTag("bar").getBoundsInRoot(),
                    )
            }
            return result
        }

        test("tooltipEnd: in a bar above a heavyweight panel the tooltip sits right of the button and never touches the panel") {
            val (button, tooltip, heavy, _) = measureBar(barAtTop = true, placement = tooltipEnd())
            tooltip.left.value shouldBeGreaterThanOrEqual button.right.value
            tooltip.intersects(heavy) shouldBe false
        }

        test("tooltipStart: in a bar below a heavyweight panel the tooltip sits left of the button and never touches the panel") {
            val (button, tooltip, heavy, _) = measureBar(barAtTop = false, placement = tooltipStart())
            tooltip.right.value shouldBeLessThanOrEqual button.left.value
            tooltip.intersects(heavy) shouldBe false
        }

        test("tooltipEnd and tooltipStart keep the tooltip inside the bar's own vertical band") {
            for ((atTop, placement) in listOf(true to tooltipEnd(), false to tooltipStart())) {
                val (_, tooltip, _, bar) = measureBar(barAtTop = atTop, placement = placement)
                tooltip.top.value shouldBeGreaterThanOrEqual bar.top.value
                tooltip.bottom.value shouldBeLessThanOrEqual bar.bottom.value
            }
        }

        test("the old placements WOULD hide the tooltip: above/below a bar next to a heavyweight panel they touch it") {
            // Documents why tooltipAbove/tooltipBelow must not be used for these bars.
            val below = measureBar(barAtTop = true, placement = tooltipBelow())
            below.tooltip.intersects(below.heavyweight) shouldBe true
            val above = measureBar(barAtTop = false, placement = tooltipAbove())
            above.tooltip.intersects(above.heavyweight) shouldBe true
        }
    })
