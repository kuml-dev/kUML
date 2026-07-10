package dev.kuml.widget.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Compose UI robot tests for [ControlPanel]'s guard-edit wiring — drives the
 * full path `ControlPanel` -> `resolveGuardEditAction` -> `changeGuard` ->
 * runtime `applyPatch`, over a real [BehaviourWidgetState] built from the
 * shared traffic-light fixture (see [buildTrafficLight]/[buildState]).
 *
 * Deliberately does **not** host [BehaviourWidget] itself — see
 * [OclGuardEditorUiTest]'s class doc for the headless-CI rationale.
 */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class ControlPanelGuardEditUiTest :
    FunSpec({

        test("EditPolicy.None hides the transitions/edit affordance entirely") {
            runComposeUiTest {
                setThemedContent { ControlPanel(state = buildState(EditPolicy.None)) }

                onNodeWithTag(EditorTestTags.TRANSITIONS_SECTION).assertDoesNotExist()
                onNodeWithTag(EditorTestTags.transitionRow("t-red-green")).assertDoesNotExist()
            }
        }

        test("GuardsOnly reveals the transitions section and per-transition rows") {
            runComposeUiTest {
                setThemedContent { ControlPanel(state = buildState(EditPolicy.GuardsOnly)) }

                onNodeWithTag(EditorTestTags.TRANSITIONS_SECTION).assertExists()
                onNodeWithTag(EditorTestTags.transitionRow("t-red-green")).assertExists()
                onNodeWithTag(EditorTestTags.transitionRow("t-yellow-red")).assertExists()
            }
        }

        test("editing a normal transition and Saving a valid guard calls changeGuard and mutates the model") {
            runComposeUiTest {
                val state = buildState(EditPolicy.GuardsOnly)
                setThemedContent { ControlPanel(state = state) }

                onNodeWithTag(EditorTestTags.transitionRow("t-red-green")).performClick()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("vars.ready")
                awaitTypeCheck()
                onNodeWithTag(EditorTestTags.GUARD_SAVE).performClick()
                waitForIdle()

                state.model.transition("t-red-green").guard shouldBe "vars.ready"
            }
        }

        test("Saving a guard edit on a protected transition opens the confirmation dialog") {
            runComposeUiTest {
                val state = buildState(EditPolicy.GuardsOnly)
                setThemedContent { ControlPanel(state = state) }

                onNodeWithTag(EditorTestTags.transitionRow("t-yellow-red")).performClick()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("true")
                awaitTypeCheck()
                onNodeWithTag(EditorTestTags.GUARD_SAVE).performClick()
                waitForIdle()

                onNodeWithTag(EditorTestTags.CONFIRM_DIALOG_TITLE).assertExists()
                state.model.transition("t-yellow-red").guard shouldBe null
            }
        }

        test("Cancel on the confirmation dialog discards the protected edit") {
            runComposeUiTest {
                val state = buildState(EditPolicy.GuardsOnly)
                setThemedContent { ControlPanel(state = state) }

                onNodeWithTag(EditorTestTags.transitionRow("t-yellow-red")).performClick()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("true")
                awaitTypeCheck()
                onNodeWithTag(EditorTestTags.GUARD_SAVE).performClick()
                waitForIdle()

                onNodeWithTag(EditorTestTags.CONFIRM_CANCEL).performClick()
                waitForIdle()

                onNodeWithTag(EditorTestTags.CONFIRM_DIALOG_TITLE).assertDoesNotExist()
                state.model.transition("t-yellow-red").guard shouldBe null
            }
        }

        test("Confirm on the dialog applies the protected edit") {
            runComposeUiTest {
                val state = buildState(EditPolicy.GuardsOnly)
                setThemedContent { ControlPanel(state = state) }

                onNodeWithTag(EditorTestTags.transitionRow("t-yellow-red")).performClick()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("true")
                awaitTypeCheck()
                onNodeWithTag(EditorTestTags.GUARD_SAVE).performClick()
                waitForIdle()

                onNodeWithTag(EditorTestTags.CONFIRM_APPLY).performClick()
                waitForIdle()

                state.model.transition("t-yellow-red").guard shouldBe "true"
            }
        }

        test("the transitions section is not editable while scrubbing") {
            runComposeUiTest {
                val state = buildState(EditPolicy.GuardsOnly)
                state.scrubTo(0)
                setThemedContent { ControlPanel(state = state) }
                waitForIdle()

                onNodeWithTag(EditorTestTags.transitionRow("t-red-green")).assertIsNotEnabled()
            }
        }
    })
