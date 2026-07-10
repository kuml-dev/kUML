package dev.kuml.widget.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Compose UI robot tests for the isolated [OclGuardEditor] composable — driven
 * via `runComposeUiTest` (desktop, off-screen Skia; see `java.awt.headless` in
 * `build.gradle.kts`). Deliberately does **not** host [BehaviourWidget]/
 * [DiagramPanel] — those embed a Batik `JSVGCanvas` inside a `SwingPanel`,
 * which needs a real display and is not headless-safe.
 *
 * Exact token→color highlighting and error→underline spans are covered by the
 * pure [OclHighlightTransformationTest]/[OclSyntaxTest]; these tests assert
 * only the *observable consequences* of the highlight/type-check pipeline —
 * text propagation, error-message node text, Save enabled/disabled — because
 * span colors are not exposed through Compose semantics.
 */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class OclGuardEditorUiTest :
    FunSpec({

        test("typing propagates into the field and re-runs the check") {
            runComposeUiTest {
                setThemedContent {
                    OclGuardEditor(initial = "", scope = guardScope, onSave = {}, onCancel = {})
                }

                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("vars.ready")
                awaitTypeCheck()

                // assertTextEquals compares the merged (label-Text + EditableText) list, so it
                // would also require the "Guard (OCL)" label here; assertTextContains checks
                // membership instead, which is what "propagated" actually means for this field.
                onNodeWithTag(EditorTestTags.GUARD_INPUT).assertTextContains("vars.ready")
                onNodeWithTag(EditorTestTags.GUARD_ERROR).assertTextEquals(" ")
                onNodeWithTag(EditorTestTags.GUARD_SAVE).assertIsEnabled()
            }
        }

        test("an unknown-variable expression shows an error message and disables Save") {
            runComposeUiTest {
                setThemedContent {
                    OclGuardEditor(initial = "", scope = guardScope, onSave = {}, onCancel = {})
                }

                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("foo")
                awaitTypeCheck()

                // The red underline itself (an `errorRange` -> TextDecoration.Underline span) is
                // asserted by OclHighlightTransformationTest; it isn't exposed via semantics, so
                // it's cross-referenced rather than re-asserted here.
                onNodeWithTag(EditorTestTags.GUARD_ERROR).assertTextEquals("unknown variable 'foo'")
                onNodeWithTag(EditorTestTags.GUARD_SAVE).assertIsNotEnabled()
            }
        }

        test("a hard syntax error also surfaces a message and blocks Save") {
            runComposeUiTest {
                setThemedContent {
                    OclGuardEditor(initial = "", scope = guardScope, onSave = {}, onCancel = {})
                }

                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("self.")
                awaitTypeCheck()

                // Message text mirrors OclParser's postfix-dot error (see OclSyntaxTest's
                // "reports a syntax error with a non-null range" case for the same input).
                onNodeWithTag(EditorTestTags.GUARD_ERROR).assertTextEquals("Expected property name after '.'")
                onNodeWithTag(EditorTestTags.GUARD_SAVE).assertIsNotEnabled()
            }
        }

        test("Save on an error-free guard invokes onSave with the current text") {
            runComposeUiTest {
                var saved: String? = null
                setThemedContent {
                    OclGuardEditor(
                        initial = "",
                        scope = guardScope,
                        onSave = { saved = it },
                        onCancel = {},
                    )
                }

                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("event")
                awaitTypeCheck()
                onNodeWithTag(EditorTestTags.GUARD_SAVE).performClick()

                saved shouldBe "event"
            }
        }

        test("Cancel invokes onCancel and never calls onSave") {
            runComposeUiTest {
                var saved: String? = null
                var cancelled = false
                setThemedContent {
                    OclGuardEditor(
                        initial = "",
                        scope = guardScope,
                        onSave = { saved = it },
                        onCancel = { cancelled = true },
                    )
                }

                onNodeWithTag(EditorTestTags.GUARD_CANCEL).performClick()

                cancelled shouldBe true
                saved shouldBe null
            }
        }

        test("Save stays disabled so an errored guard cannot be committed") {
            runComposeUiTest {
                var saved: String? = null
                setThemedContent {
                    OclGuardEditor(
                        initial = "",
                        scope = guardScope,
                        onSave = { saved = it },
                        onCancel = {},
                    )
                }

                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("foo")
                awaitTypeCheck()

                onNodeWithTag(EditorTestTags.GUARD_SAVE).assertIsNotEnabled()
                saved shouldBe null
            }
        }
    })
