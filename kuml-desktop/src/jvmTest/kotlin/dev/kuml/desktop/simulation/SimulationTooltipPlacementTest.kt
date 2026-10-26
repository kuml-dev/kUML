package dev.kuml.desktop.simulation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.tooltipStart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual

/**
 * The edit and revert buttons sit at the right edge of the side panel, which itself sits next to
 * the heavyweight preview canvas. Their tooltips must open to the left but stay inside the
 * panel, otherwise the canvas hides them.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class SimulationTooltipPlacementTest :
    FunSpec({
        for (strings in listOf(Strings.EN, Strings.DE)) {
            for ((icon, description) in listOf(
                KumlIcons.EditGuard to strings.simEditGuard,
                KumlIcons.RevertGuard to strings.simRevertGuard,
            )) {
                test("tooltip '$description' stays inside the narrowest side panel and clear of the canvas") {
                    runComposeUiTest {
                        setContent {
                            MaterialTheme {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().testTag("canvas"))
                                    Column(modifier = Modifier.width(SIDE_PANEL_MIN_DP.dp).testTag("panel")) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            IconTooltipButton(
                                                icon = icon,
                                                description = description,
                                                onClick = {},
                                                tooltipPlacement = tooltipStart(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        onNodeWithContentDescription(description).performMouseInput { moveTo(center) }
                        mainClock.advanceTimeBy(1_000)
                        waitForIdle()
                        val tooltip = onNodeWithText(description).getBoundsInRoot()
                        val panel = onNodeWithTag("panel").getBoundsInRoot()
                        val canvas = onNodeWithTag("canvas").getBoundsInRoot()
                        tooltip.left.value shouldBeGreaterThanOrEqual panel.left.value
                        tooltip.left.value shouldBeGreaterThanOrEqual canvas.right.value
                        tooltip.right.value shouldBeLessThanOrEqual panel.right.value
                    }
                }
            }
        }
    })
