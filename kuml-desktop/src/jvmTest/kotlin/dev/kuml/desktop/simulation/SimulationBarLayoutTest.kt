package dev.kuml.desktop.simulation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan

/**
 * Regression for the blank diagram area during a simulation: the bar sits above the preview's `SwingPanel`
 * inside a height-bounded `Column`. A child that fills the maximum height (Material 3's `VerticalDivider`
 * does) stretched the bar to the whole window, leaving the panel below with zero height. The bar must stay
 * compact and the area below it must keep (almost) all of the remaining height.
 */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class SimulationBarLayoutTest :
    FunSpec({
        test("the simulation bar stays compact and leaves the area below it its height") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent {
                        Column(Modifier.fillMaxWidth().height(700.dp).testTag("host")) {
                            SimulationBar(state = AppState(), session = session, strings = Strings.EN)
                            Box(Modifier.fillMaxWidth().weight(1f).testTag("below"))
                        }
                    }
                    waitForIdle()
                    val below = onNodeWithTag("below")
                    below.assertHeightIsAtLeast(550.dp)
                    // Test density is 1, so pixels equal dp here.
                    val barHeightPx = onNodeWithTag("host").fetchSemanticsNode().size.height - below.fetchSemanticsNode().size.height
                    barHeightPx shouldBeLessThan 150
                }
            } finally {
                session.close()
            }
        }
    })
