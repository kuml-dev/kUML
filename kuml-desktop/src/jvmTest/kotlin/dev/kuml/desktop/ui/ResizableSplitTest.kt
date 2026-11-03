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
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.AppSettings
import dev.kuml.desktop.workspace.WORKSPACE_EDITOR_DIVIDER_TAG
import dev.kuml.desktop.workspace.WORKSPACE_NAV_DIVIDER_TAG
import dev.kuml.desktop.workspace.WorkspaceColumns
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
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

        // ---- Knowledge Workspace columns (two nested splits) ----

        // Navigation split: 200 dp start, 408 dp end (2 x 200 + 8 divider), share 0.15..0.40, fallback 0.2.
        fun navBounds(totalPx: Float) =
            splitBounds(totalPx = totalPx, minPanePx = 200f, endMinPx = 408f, maxFraction = 0.40, fallback = 0.2)

        fun navClamp(
            fraction: Double,
            totalPx: Float,
        ) = clampSplitFraction(
            fraction = fraction,
            totalPx = totalPx,
            minPanePx = 200f,
            endMinPx = 408f,
            maxFraction = 0.40,
            fallback = 0.2,
        )

        test("navigation bounds at wide windows") {
            // 200/1392 = 0.1437 < 0.15 -> fraction limit wins
            navBounds(1392f).start shouldBe (0.15 plusOrMinus 1e-9)
            navBounds(1392f).endInclusive shouldBe (0.40 plusOrMinus 1e-9)
            // 900 dp window: usable 892, 200/892 = 0.224, 1 - 408/892 = 0.5426 -> capped at 0.40
            navBounds(892f).start shouldBe (200.0 / 892.0 plusOrMinus 1e-6)
            navBounds(892f).endInclusive shouldBe (0.40 plusOrMinus 1e-9)
        }

        test("navigation bounds collapse to a valid single value at exactly 616 dp") {
            // usable 608: 200/608 and 1 - 408/608 are mathematically equal (floating point may differ by 1 ULP)
            val b = navBounds(608f)
            (b.start <= b.endInclusive) shouldBe true
            b.start shouldBe (200.0 / 608.0 plusOrMinus 1e-6)
            b.endInclusive shouldBe (200.0 / 608.0 plusOrMinus 1e-6)
            navClamp(0.2, 608f) shouldBe (200.0 / 608.0 plusOrMinus 1e-6)
        }

        test("navigation falls back to exactly 0.2 below 616 dp") {
            val usable = 607f // 615 dp window
            navBounds(usable) shouldBe (0.2..0.2)
            navClamp(0.35, usable) shouldBe 0.2
            navClamp(0.0, 400f) shouldBe 0.2
        }

        test("navigation upper limit 0.40 applies and NaN / infinity give 0.2") {
            navClamp(0.9, 2000f) shouldBe (0.40 plusOrMinus 1e-9)
            navClamp(Double.NaN, 2000f) shouldBe 0.2
            navClamp(Double.POSITIVE_INFINITY, 2000f) shouldBe 0.2
            navClamp(Double.NEGATIVE_INFINITY, 2000f) shouldBe 0.2
        }

        test("editor bounds follow 200 dp minimum on both sides and fall back to 0.5") {
            val wide = splitBounds(totalPx = 2000f, minPanePx = 200f, maxFraction = 0.85)
            wide.start shouldBe (0.15 plusOrMinus 1e-9)
            wide.endInclusive shouldBe (0.85 plusOrMinus 1e-9)
            val mid = splitBounds(totalPx = 1138f, minPanePx = 200f, maxFraction = 0.85)
            mid.start shouldBe (200.0 / 1138.0 plusOrMinus 1e-6)
            mid.endInclusive shouldBe (938.0 / 1138.0 plusOrMinus 1e-6)
            val narrow = splitBounds(totalPx = 600f, minPanePx = 200f, maxFraction = 0.85)
            narrow.start shouldBe (200.0 / 600.0 plusOrMinus 1e-6)
            narrow.endInclusive shouldBe (400.0 / 600.0 plusOrMinus 1e-6)
            splitBounds(totalPx = 300f, minPanePx = 200f, maxFraction = 0.85, fallback = 0.5) shouldBe (0.5..0.5)
        }

        test("existing split bounds are unchanged for default arguments") {
            splitBounds(totalPx = 1000f, minPanePx = 100f).start shouldBe 0.15
            splitBounds(totalPx = 1000f, minPanePx = 100f).endInclusive shouldBe (0.85 plusOrMinus 1e-9)
        }

        class ColumnProbe {
            var nav by mutableStateOf(0.2)
            var editor by mutableStateOf(0.5)
            var navChanges = 0
            var editorChanges = 0
            var navCommits = 0
            var editorCommits = 0
            var lastNavCommit = Double.NaN
            var lastEditorCommit = Double.NaN
        }

        fun androidx.compose.ui.test.ComposeUiTest.showColumns(
            p: ColumnProbe,
            width: Int = 1440,
        ) {
            setContent {
                MaterialTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxSize()) {
                        WorkspaceColumns(
                            navFraction = p.nav,
                            onNavFractionChange = {
                                p.navChanges++
                                p.nav = it
                            },
                            onNavFractionCommit = {
                                p.navCommits++
                                p.lastNavCommit = it
                            },
                            editorFraction = p.editor,
                            onEditorFractionChange = {
                                p.editorChanges++
                                p.editor = it
                            },
                            onEditorFractionCommit = {
                                p.editorCommits++
                                p.lastEditorCommit = it
                            },
                            strings = Strings.EN,
                            modifier = Modifier.fillMaxSize(),
                            tree = { Box(Modifier.fillMaxSize().testTag("tree")) },
                            editor = { Box(Modifier.fillMaxSize().testTag("editor")) },
                            preview = { Box(Modifier.fillMaxSize().testTag("preview")) },
                        )
                    }
                }
            }
        }

        fun androidx.compose.ui.test.ComposeUiTest.widths() =
            Triple(
                onNodeWithTag("tree").getBoundsInRoot().width.value,
                onNodeWithTag("editor").getBoundsInRoot().width.value,
                onNodeWithTag("preview").getBoundsInRoot().width.value,
            )

        test("default layout at 1440 dp is about 286 / 569 / 569 dp") {
            runComposeUiTest {
                showColumns(ColumnProbe())
                val (t, e, v) = widths()
                // outer usable = 1432 -> 20 % = 286.4; rest 1145.6 - 8 = 1137.6 -> 568.8 each
                t shouldBe (286.4f plusOrMinus 1.5f)
                e shouldBe (568.8f plusOrMinus 1.5f)
                v shouldBe (568.8f plusOrMinus 1.5f)
            }
        }

        test("both dividers are addressable separately and carry their own descriptions") {
            runComposeUiTest {
                showColumns(ColumnProbe())
                val nav = onNodeWithTag(WORKSPACE_NAV_DIVIDER_TAG).fetchSemanticsNode().config
                val ed = onNodeWithTag(WORKSPACE_EDITOR_DIVIDER_TAG).fetchSemanticsNode().config
                nav[SemanticsProperties.ContentDescription] shouldBe listOf(Strings.EN.workspaceLayout.navDividerDescription)
                ed[SemanticsProperties.ContentDescription] shouldBe listOf(Strings.EN.workspaceLayout.editorDividerDescription)
                nav[SemanticsProperties.StateDescription] shouldBe "20 %"
                ed[SemanticsProperties.StateDescription] shouldBe "50 %"
            }
        }

        test("double click on the outer divider resets to 0.2 and leaves the editor share alone") {
            val p =
                ColumnProbe().also {
                    it.nav = 0.35
                    it.editor = 0.7
                }
            runComposeUiTest {
                showColumns(p)
                onNodeWithTag(WORKSPACE_NAV_DIVIDER_TAG).performMouseInput { doubleClick() }
                waitForIdle()
            }
            p.nav shouldBe 0.2
            p.lastNavCommit shouldBe 0.2
            p.editor shouldBe 0.7
            p.editorChanges shouldBe 0
            p.editorCommits shouldBe 0
        }

        test("double click on the inner divider resets to 0.5 and leaves the nav share alone") {
            val p =
                ColumnProbe().also {
                    it.nav = 0.3
                    it.editor = 0.7
                }
            runComposeUiTest {
                showColumns(p)
                onNodeWithTag(WORKSPACE_EDITOR_DIVIDER_TAG).performMouseInput { doubleClick() }
                waitForIdle()
            }
            p.editor shouldBe 0.5
            p.lastEditorCommit shouldBe 0.5
            p.nav shouldBe 0.3
            p.navCommits shouldBe 0
        }

        test("reset in a narrow window stores the default, not the clamped value") {
            // 700 dp window: usable 692, nav upper bound = 1 - 408/692 = 0.41 -> 0.40, lower 200/692 = 0.289
            val p = ColumnProbe().also { it.nav = 0.35 }
            runComposeUiTest {
                showColumns(p, width = 700)
                onNodeWithTag(WORKSPACE_NAV_DIVIDER_TAG).performMouseInput { doubleClick() }
                waitForIdle()
            }
            p.lastNavCommit shouldBe 0.2
            p.nav shouldBe 0.2
        }

        test("dragging the outer divider does not change the editor share but scales editor and preview") {
            val p = ColumnProbe()
            var before = Triple(0f, 0f, 0f)
            var after = Triple(0f, 0f, 0f)
            runComposeUiTest {
                showColumns(p)
                before = widths()
                onNodeWithTag(WORKSPACE_NAV_DIVIDER_TAG).performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(100f, 0f))
                    release()
                }
                waitForIdle()
                after = widths()
            }
            p.navCommits shouldBe 1
            (p.nav > 0.2) shouldBe true
            p.editorChanges shouldBe 0
            p.editor shouldBe 0.5
            (after.first > before.first) shouldBe true
            (after.second < before.second) shouldBe true
            after.second shouldBe (after.third plusOrMinus 1.5f)
        }

        test("dragging the inner divider commits only the editor share") {
            val p = ColumnProbe()
            runComposeUiTest {
                showColumns(p)
                onNodeWithTag(WORKSPACE_EDITOR_DIVIDER_TAG).performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(Offset(80f, 0f))
                    release()
                }
                waitForIdle()
            }
            p.editorCommits shouldBe 1
            (p.editor > 0.5) shouldBe true
            p.navChanges shouldBe 0
            p.navCommits shouldBe 0
        }

        test("wishes survive shrinking and re-growing the window") {
            val p =
                ColumnProbe().also {
                    it.nav = 0.15
                    it.editor = 0.85
                }
            runComposeUiTest {
                showColumns(p, width = 700)
                val (t, e, v) = widths()
                (t >= 199f) shouldBe true
                (e >= 199f) shouldBe true
                (v >= 199f) shouldBe true
            }
            p.nav shouldBe 0.15
            p.editor shouldBe 0.85
            p.navChanges shouldBe 0
            p.editorChanges shouldBe 0
        }

        test("below 616 dp the layout is the default 20 / 40 / 40 without minimums") {
            val p =
                ColumnProbe().also {
                    it.nav = 0.4
                    it.editor = 0.8
                }
            runComposeUiTest {
                showColumns(p, width = 500)
                val (t, e, v) = widths()
                // outer usable 492 -> 98.4; rest 393.6 - 8 = 385.6 -> 192.8 each
                t shouldBe (98.4f plusOrMinus 1.5f)
                e shouldBe (192.8f plusOrMinus 1.5f)
                v shouldBe (192.8f plusOrMinus 1.5f)
            }
        }

        test("AppState clamps workspace shares on load and sanitises NaN") {
            AppState(AppSettings(workspaceNavFraction = 2.0)).workspaceNavFraction shouldBe (0.40 plusOrMinus 1e-9)
            AppState(AppSettings(workspaceNavFraction = -1.0)).workspaceNavFraction shouldBe 0.15
            AppState(AppSettings(workspaceNavFraction = Double.NaN)).workspaceNavFraction shouldBe 0.2
            AppState(AppSettings(workspaceNavFraction = 0.7)).workspaceNavFraction shouldBe (0.40 plusOrMinus 1e-9)
            AppState(AppSettings(workspaceEditorFraction = 2.0)).workspaceEditorFraction shouldBe (0.85 plusOrMinus 1e-9)
            AppState(AppSettings(workspaceEditorFraction = -1.0)).workspaceEditorFraction shouldBe 0.15
            AppState(AppSettings(workspaceEditorFraction = Double.NaN)).workspaceEditorFraction shouldBe 0.5
            AppState().workspaceNavFraction shouldBe 0.2
            AppState().workspaceEditorFraction shouldBe 0.5
        }

        test("workspace live fractions do not touch the committed values; toSettings carries them") {
            val s = AppState(AppSettings(workspaceNavFraction = 0.3, workspaceEditorFraction = 0.6))
            s.workspaceNavFractionCommitted shouldBe s.workspaceNavFraction
            s.workspaceEditorFractionCommitted shouldBe s.workspaceEditorFraction
            s.workspaceNavFraction = 0.25
            s.workspaceEditorFraction = 0.7
            s.workspaceNavFractionCommitted shouldBe 0.3
            s.workspaceEditorFractionCommitted shouldBe 0.6
            s.toSettings().workspaceNavFraction shouldBe 0.25
            s.toSettings().workspaceEditorFraction shouldBe 0.7
        }
    })
