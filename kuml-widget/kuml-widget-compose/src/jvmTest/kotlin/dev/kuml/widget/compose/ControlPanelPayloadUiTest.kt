package dev.kuml.widget.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class ControlPanelPayloadUiTest :
    FunSpec({

        test("an invalid payload shows an inline error and disables Send") {
            runComposeUiTest {
                setThemedContent { ControlPanel(state = buildState(EditPolicy.None)) }

                onNodeWithTag(EditorTestTags.PAYLOAD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.PAYLOAD_INPUT).performTextInput("{nope")
                mainClock.advanceTimeBy(PAYLOAD_VALIDATION_DEBOUNCE_MS + 50)
                waitForIdle()

                onNodeWithTag(EditorTestTags.PAYLOAD_ERROR, useUnmergedTree = true).assertTextContains("Invalid JSON", substring = true)
                onNodeWithTag(EditorTestTags.SEND_BUTTON).assertIsNotEnabled()
            }
        }

        test("a valid payload keeps Send enabled and shows no error") {
            runComposeUiTest {
                setThemedContent { ControlPanel(state = buildState(EditPolicy.None)) }

                onNodeWithTag(EditorTestTags.PAYLOAD_INPUT).performTextClearance()
                onNodeWithTag(EditorTestTags.PAYLOAD_INPUT).performTextInput("""{"amount": 3}""")
                mainClock.advanceTimeBy(PAYLOAD_VALIDATION_DEBOUNCE_MS + 50)
                waitForIdle()

                onNodeWithTag(EditorTestTags.PAYLOAD_ERROR, useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag(EditorTestTags.SEND_BUTTON).assertIsEnabled()
            }
        }
    })
