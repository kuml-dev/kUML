package dev.kuml.desktop.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.kuml.desktop.i18n.Strings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The toolbar's third export button: always present, disabled with the caller's reason, enabled with a handler. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ZoomToolbarLatexTest :
    FunSpec({
        val strings = Strings.EN

        test("without a handler the LaTeX button stays visible, is disabled and explains why") {
            runComposeUiTest {
                setContent {
                    ZoomToolbar(
                        canvas = createPreviewCanvas(),
                        strings = strings,
                        exportSvg = "<svg/>",
                        onExport = {},
                        onExportLatex = null,
                        latexDisabledReason = strings.latexExport.unsupportedDiagram,
                    )
                }
                onNodeWithContentDescription(strings.latexExport.unsupportedDiagram).assertIsNotEnabled()
            }
        }

        test("without a reason the disabled button falls back to the nothing-to-save text") {
            runComposeUiTest {
                setContent { ZoomToolbar(canvas = createPreviewCanvas(), strings = strings) }
                // SVG, PNG and LaTeX are all disabled with the same text when nothing is rendered.
                val nodes = onAllNodesWithContentDescription(strings.previewExport.nothingToSave)
                nodes.assertCountEquals(3)
                (0 until 3).forEach { nodes[it].assertIsNotEnabled() }
            }
        }

        test("with a handler the button is enabled, has the save tooltip and calls the handler once per click") {
            var calls = 0
            runComposeUiTest {
                setContent {
                    ZoomToolbar(
                        canvas = createPreviewCanvas(),
                        strings = strings,
                        exportSvg = "<svg/>",
                        onExport = {},
                        onExportLatex = { calls++ },
                    )
                }
                onNodeWithContentDescription(strings.latexExport.toolbarTooltip).assertIsEnabled().performClick()
            }
            calls shouldBe 1
        }
    })
