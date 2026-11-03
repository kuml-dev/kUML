package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Compose UI tests of the status area; semantics and text only, never pixels. */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class PdfStatusAreaUiTest :
    FunSpec({
        val en = Strings.EN

        fun warning(offerA3: Boolean) =
            PdfStatusView.Warning(
                failureCount = 0,
                scaleCount = 1,
                failureSample = emptyList(),
                scaleSample = listOf("a.md#1"),
                hiddenCount = 0,
                layoutNote = null,
                offerA3Landscape = offerA3,
                hintMinScale = false,
            )

        test("warning shows both actions and they trigger their callbacks") {
            var a3 = 0
            var anyway = 0
            runComposeUiTest {
                setContent {
                    Box(Modifier.heightIn(max = 168.dp)) {
                        PdfStatusArea(
                            view = warning(true),
                            strings = en,
                            onUseA3 = { a3++ },
                            onExportAnyway = { anyway++ },
                            onOverwrite = {},
                            onCancelOverwrite = {},
                        )
                    }
                }
                onNodeWithText(en.pdfStatus.useA3Landscape).performClick()
                onNodeWithText(en.pdfExportAnyway).performClick()
            }
            a3 shouldBe 1
            anyway shouldBe 1
        }

        test("without the A3 offer the button is absent") {
            runComposeUiTest {
                setContent {
                    Box(Modifier.heightIn(max = 168.dp)) {
                        PdfStatusArea(
                            view = warning(false),
                            strings = en,
                            onUseA3 = {},
                            onExportAnyway = {},
                            onOverwrite = {},
                            onCancelOverwrite = {},
                        )
                    }
                }
                onAllNodesWithText(en.pdfStatus.useA3Landscape).fetchSemanticsNodes().size shouldBe 0
                onNodeWithText(en.pdfExportAnyway).assertExists()
            }
        }

        test("overwrite shows overwrite and cancel; idle shows only a hint line") {
            var overwrite = 0
            var cancel = 0
            runComposeUiTest {
                val view = androidx.compose.runtime.mutableStateOf<PdfStatusView>(PdfStatusView.Overwrite(count = 0, names = emptyList()))
                setContent {
                    Box(Modifier.heightIn(max = 168.dp)) {
                        PdfStatusArea(
                            view = view.value,
                            strings = en,
                            onUseA3 = {},
                            onExportAnyway = {},
                            onOverwrite = { overwrite++ },
                            onCancelOverwrite = { cancel++ },
                        )
                    }
                }
                onNodeWithText(en.pdfStatus.overwriteButton).performClick()
                onNodeWithText(en.pdfCancel).performClick()
                view.value = PdfStatusView.Idle
                waitForIdle()
                onNodeWithText(en.pdfStatus.idleHint).assertExists()
                onAllNodesWithText(en.pdfStatus.overwriteButton).fetchSemanticsNodes().size shouldBe 0
            }
            overwrite shouldBe 1
            cancel shouldBe 1
        }
    })
