package dev.kuml.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.mcp.McpAvailabilityHint
import dev.kuml.desktop.mcp.McpTokenButtons
import dev.kuml.desktop.workspace.PdfStatusArea
import dev.kuml.desktop.workspace.PdfStatusView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Geometry only (bounds, line count), no pixel references: independent of the platform font. */
@OptIn(ExperimentalTestApi::class)
class DialogButtonOverflowTest :
    FunSpec({
        val widthDp = 480
        val warning =
            PdfStatusView.Warning(
                failureCount = 0,
                scaleCount = 1,
                failureSample = emptyList(),
                scaleSample = listOf("a.md#1"),
                hiddenCount = 0,
                layoutNote = null,
                offerA3Landscape = true,
                hintMinScale = false,
            )

        fun androidx.compose.ui.test.ComposeUiTest.assertNoEllipsis(name: String) {
            // No label is ellipsized.
            val texts =
                onAllNodes(
                    androidx.compose.ui.test
                        .hasText("", substring = true),
                ).fetchSemanticsNodes()
            texts.forEach { n ->
                val results = mutableListOf<TextLayoutResult>()
                n.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(results)
                results.forEach { r ->
                    withClue("$name: text is ellipsized or wrapped: ${r.layoutInput.text}") {
                        (0 until r.lineCount).none { r.isLineEllipsized(it) } shouldBe true
                    }
                }
            }
        }

        fun checkTextOnly(
            name: String,
            content: @androidx.compose.runtime.Composable () -> Unit,
        ) {
            runComposeUiTest {
                setContent { MaterialTheme { Box(Modifier.width(widthDp.dp)) { content() } } }
                val texts =
                    onAllNodes(
                        androidx.compose.ui.test
                            .hasText("", substring = true),
                    ).fetchSemanticsNodes()
                withClue("$name: text nodes found") { (texts.size >= 2) shouldBe true }
                texts.forEach { n ->
                    withClue("$name: text bounds ${n.boundsInRoot}") {
                        (n.boundsInRoot.left >= 0f) shouldBe true
                        (n.boundsInRoot.right <= widthDp.toFloat()) shouldBe true
                    }
                }
                assertNoEllipsis(name)
            }
        }

        fun check(
            name: String,
            content: @androidx.compose.runtime.Composable () -> Unit,
        ) {
            runComposeUiTest {
                setContent { MaterialTheme { Box(Modifier.width(widthDp.dp)) { content() } } }
                val buttons = onAllNodes(hasClickAction()).fetchSemanticsNodes()
                withClue("$name: buttons found") { (buttons.size >= 2) shouldBe true }
                buttons.forEach { n ->
                    withClue("$name: button bounds ${n.boundsInRoot}") {
                        (n.boundsInRoot.left >= 0f) shouldBe true
                        (n.boundsInRoot.right <= widthDp.toFloat()) shouldBe true
                    }
                }
                assertNoEllipsis(name)
            }
        }

        listOf("DE" to Strings.DE, "EN" to Strings.EN).forEach { (lang, strings) ->
            test("PdfStatusArea warning buttons fit 480 dp ($lang)") {
                check("warning $lang") {
                    PdfStatusArea(
                        view = warning,
                        strings = strings,
                        onUseA3 = {},
                        onExportAnyway = {},
                        onOverwrite = {},
                        onCancelOverwrite = {},
                    )
                }
            }
            test("PdfStatusArea overwrite buttons fit 480 dp ($lang)") {
                check("overwrite $lang") {
                    PdfStatusArea(
                        view = PdfStatusView.Overwrite(count = 0, names = emptyList()),
                        strings = strings,
                        onUseA3 = {},
                        onExportAnyway = {},
                        onOverwrite = {},
                        onCancelOverwrite = {},
                    )
                }
            }
            test("McpAvailabilityHint fits 480 dp ($lang)") {
                checkTextOnly("mcp hint $lang") { McpAvailabilityHint(strings) }
            }
            test("McpTokenButtons fit 480 dp ($lang)") {
                check("mcp $lang") { McpTokenButtons(strings = strings, onCopyToken = {}, onCopyCommand = {}, onRegenerate = {}) }
            }
        }
    })
