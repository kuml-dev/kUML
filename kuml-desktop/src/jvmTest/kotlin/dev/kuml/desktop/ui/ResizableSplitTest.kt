package dev.kuml.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.kuml.desktop.AppState
import dev.kuml.desktop.io.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

@OptIn(ExperimentalTestApi::class)
class ResizableSplitTest :
    FunSpec({
        test("clamp keeps in-range values") {
            clampSplitFraction(fraction = 0.4, totalPx = 1000f, minPanePx = 100f) shouldBe 0.4
        }
        test("clamp enforces fractional bounds") {
            clampSplitFraction(fraction = 0.01, totalPx = 1000f, minPanePx = 100f) shouldBe 0.15
            clampSplitFraction(fraction = 0.99, totalPx = 1000f, minPanePx = 100f) shouldBe (0.85 plusOrMinus 1e-9)
        }
        test("clamp enforces minimum pane pixels in narrow windows") {
            clampSplitFraction(fraction = 0.1, totalPx = 600f, minPanePx = 200f) shouldBe (200.0 / 600.0 plusOrMinus 1e-6)
        }
        test("clamp returns 0.5 when both minimums cannot be met") {
            clampSplitFraction(fraction = 0.1, totalPx = 300f, minPanePx = 200f) shouldBe 0.5
        }
        test("clamp maps NaN and infinities to 0.5") {
            clampSplitFraction(fraction = Double.NaN, totalPx = 1000f, minPanePx = 100f) shouldBe 0.5
            clampSplitFraction(fraction = Double.POSITIVE_INFINITY, totalPx = 1000f, minPanePx = 100f) shouldBe 0.5
            clampSplitFraction(fraction = Double.NEGATIVE_INFINITY, totalPx = 1000f, minPanePx = 100f) shouldBe 0.5
        }
        test("clamp with unknown width uses fractional bounds only") {
            clampSplitFraction(fraction = 5.0, totalPx = Float.POSITIVE_INFINITY, minPanePx = 0f) shouldBe (0.85 plusOrMinus 1e-9)
            clampSplitFraction(fraction = 0.5, totalPx = 0f, minPanePx = 200f) shouldBe 0.5
            clampSplitFraction(fraction = -1.0, totalPx = -5f, minPanePx = 200f) shouldBe 0.15
        }
        test("fractionFromPointer centres the divider under the pointer") {
            fractionFromPointer(pointerXInRow = 504f, rowWidthPx = 1008f, dividerPx = 16f) shouldBe 0.5
            fractionFromPointer(pointerXInRow = 8f, rowWidthPx = 1008f, dividerPx = 16f) shouldBe 0.0
            fractionFromPointer(pointerXInRow = 1000f, rowWidthPx = 1008f, dividerPx = 16f) shouldBe 1.0
            fractionFromPointer(pointerXInRow = 5f, rowWidthPx = 10f, dividerPx = 16f) shouldBe 0.5
        }

        class Probe {
            var fraction by mutableStateOf(0.5)
            var changes = 0
            var commits = 0
            var lastCommit = Double.NaN
        }

        fun androidx.compose.ui.test.ComposeUiTest.show(
            probe: Probe,
            width: Int = 1000,
        ) {
            setContent {
                MaterialTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxSize()) {
                        ResizableSplit(
                            fraction = probe.fraction,
                            onFractionChange = {
                                probe.changes++
                                probe.fraction = it
                            },
                            onFractionCommit = {
                                probe.commits++
                                probe.lastCommit = it
                            },
                            dividerDescription = "Divider",
                            modifier = Modifier.fillMaxSize(),
                            start = { Box(Modifier.fillMaxSize().testTag("start")) },
                            end = { Box(Modifier.fillMaxSize().testTag("end")) },
                        )
                    }
                }
            }
        }

        test("drag moves the divider and commits exactly once on release") {
            val p = Probe()
            runComposeUiTest {
                show(p)
                onNodeWithTag(SPLIT_DIVIDER_TEST_TAG).performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(100f, 0f))
                    moveBy(Offset(50f, 0f))
                    release()
                }
                waitForIdle()
            }
            p.commits shouldBe 1
            (p.fraction > 0.5) shouldBe true
            p.lastCommit shouldBe p.fraction
        }

        test("drag far left is clamped to the minimum") {
            val p = Probe()
            runComposeUiTest {
                show(p)
                onNodeWithTag(SPLIT_DIVIDER_TEST_TAG).performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(-5000f, 0f))
                    release()
                }
                waitForIdle()
            }
            (p.fraction >= 0.2 - 1e-6) shouldBe true
            (p.fraction <= 0.25) shouldBe true
        }

        test("cancelled gesture does not commit twice") {
            val p = Probe()
            runComposeUiTest {
                show(p)
                onNodeWithTag(SPLIT_DIVIDER_TEST_TAG).performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(40f, 0f))
                    cancel()
                }
                waitForIdle()
            }
            (p.commits <= 1) shouldBe true
        }

        test("double click resets to 0.5 and commits") {
            val p = Probe().also { it.fraction = 0.3 }
            runComposeUiTest {
                show(p)
                onNodeWithTag(SPLIT_DIVIDER_TEST_TAG).performMouseInput { doubleClick() }
                waitForIdle()
            }
            p.fraction shouldBe 0.5
            (p.commits >= 1) shouldBe true
        }

        test("semantics expose description, state and range; SetProgress commits") {
            val p = Probe()
            runComposeUiTest {
                show(p)
                val node = onNodeWithTag(SPLIT_DIVIDER_TEST_TAG)
                val cfg = node.fetchSemanticsNode().config
                cfg[SemanticsProperties.ContentDescription] shouldBe listOf("Divider")
                cfg[SemanticsProperties.StateDescription] shouldBe "50 %"
                cfg[SemanticsProperties.ProgressBarRangeInfo].current shouldBe 0.5f
                node.performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
                waitForIdle()
            }
            (p.fraction - 0.3).let { kotlin.math.abs(it) < 1e-6 } shouldBe true
            p.commits shouldBe 1
        }

        test("shrinking the container clamps layout but keeps the wish") {
            val p = Probe().also { it.fraction = 0.15 }
            var startWidth = 0f
            runComposeUiTest {
                show(p, width = 600)
                startWidth = onNodeWithTag("start").getBoundsInRoot().width.value
            }
            // 200 dp minimum pane wins over 15 % of 600 dp.
            (startWidth >= 199f) shouldBe true
            p.fraction shouldBe 0.15
            p.changes shouldBe 0
        }

        test("AppState clamps stored fraction and sanitises NaN") {
            AppState(AppSettings(editorSplitFraction = 2.0)).editorSplitFraction shouldBe (0.85 plusOrMinus 1e-9)
            AppState(AppSettings(editorSplitFraction = -1.0)).editorSplitFraction shouldBe 0.15
            AppState(AppSettings(editorSplitFraction = Double.NaN)).editorSplitFraction shouldBe 0.5
            AppState().editorSplitFraction shouldBe 0.5
        }

        test("live fraction does not touch the persistence trigger; toSettings carries it") {
            val s = AppState()
            s.editorSplitFraction = 0.3
            s.editorSplitFractionCommitted shouldBe 0.5
            s.toSettings().editorSplitFraction shouldBe 0.3
        }
    })
