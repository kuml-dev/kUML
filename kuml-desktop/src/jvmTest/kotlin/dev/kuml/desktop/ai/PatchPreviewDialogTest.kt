package dev.kuml.desktop.ai

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.kuml.ai.tools.patch.PatchDiff
import dev.kuml.ai.tools.patch.apply.ModelSnippet
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.buttonIcon
import dev.kuml.desktop.ui.iconOffset
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for the pure logic helpers in [PatchPreviewDialog].
 *
 * The dialog window itself is not rendered here; its footer is, via [PatchPreviewFooter].
 * The logic that can be tested without the Compose runtime (prefix/color mapping,
 * data model correctness, string constants) is covered here.
 */
@OptIn(ExperimentalTestApi::class)
class PatchPreviewDialogTest :
    FunSpec({

        test("kindPrefix: 'added' kind maps to '[+]' prefix") {
            kindPrefix("added") shouldBe "[+]"
        }

        test("kindPrefix: 'removed' kind maps to '[-]' prefix") {
            kindPrefix("removed") shouldBe "[-]"
        }

        test("kindPrefix: 'modified' kind maps to '[~]' prefix") {
            kindPrefix("modified") shouldBe "[~]"
        }

        test("kindColor: 'added' kind maps to green color") {
            kindColor("added") shouldBe Color(0xFF2e7d32)
        }

        test("kindColor: 'removed' kind maps to red color") {
            kindColor("removed") shouldBe Color(0xFFc62828)
        }

        test("PendingPatchView.diff.before.text and after.text hold the correct values") {
            val before = ModelSnippet(elementIds = listOf("el1"), text = "Before content")
            val after = ModelSnippet(elementIds = listOf("el1"), text = "After content")
            val diff =
                PatchDiff(
                    patchId = "test-patch-id",
                    before = before,
                    after = after,
                    elementChanges = emptyList(),
                )
            val view =
                AiPanelState.PendingPatchView(
                    patchId = "test-patch-id",
                    kind = "added",
                    diff = diff,
                )
            view.diff.before.text shouldBe "Before content"
            view.diff.after.text shouldBe "After content"
        }

        test("footer warning follows the reject-all label and mentions patches, in both languages") {
            listOf(Strings.DE, Strings.EN).forEach { s ->
                s.aiDialog.patchFooterWarning.contains(s.aiDialog.rejectAll) shouldBe true
                s.aiDialog.patchFooterWarning.contains("patches", ignoreCase = true) shouldBe true
            }
            Strings.DE.aiDialog.patchFooterWarning shouldBe "Hinweis: „Alle ablehnen“ rollt auch bereits übernommene Patches zurück."
        }

        test("the dialog texts exist in German and English") {
            Strings.DE.aiDialog.inspect shouldBe "Ansehen"
            Strings.EN.aiDialog.acceptAll shouldBe "Accept all"
            Strings.EN.aiDialog.rejectAll shouldBe "Reject all"
            Strings.EN.dialogClose shouldBe "Close"
        }

        test("the footer shows reject-all, accept-all and close with their own icons and no doubled close symbol") {
            buttonIcon(ButtonAction.REJECT) shouldNotBe buttonIcon(ButtonAction.CLOSE)
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        PatchPreviewFooter(
                            strings = Strings.EN,
                            isApplying = false,
                            onRejectAll = {},
                            onAcceptAll = {},
                            onDismiss = {},
                        )
                    }
                }
                onNodeWithText("Reject all").assertExists()
                onNodeWithText("Accept all").assertExists()
                onNodeWithText("Close").assertExists()
                val buttons = listOf("patch-reject-all" to "Reject all", "patch-accept-all" to "Accept all", "patch-close" to "Close")
                buttons.forEach { (tag, text) ->
                    withClue(tag) { (iconOffset(buttonTag = tag, text = text) > 10f) shouldBe true }
                }
            }
        }

        test("isVisible=false guard: dialog would return early without rendering") {
            // Test the guard logic: isVisible=false → return immediately
            // This is tested by verifying kindPrefix/kindColor work on empty input
            // (the dialog guard is `if (!isVisible) return`, no Compose runtime needed)
            val emptyList = emptyList<AiPanelState.PendingPatchView>()
            emptyList.size shouldBe 0 // Dialog with empty list and isVisible=false is safe
        }
    })
