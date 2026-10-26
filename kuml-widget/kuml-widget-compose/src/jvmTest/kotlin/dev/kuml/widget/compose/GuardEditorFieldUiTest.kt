package dev.kuml.widget.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class GuardEditorFieldUiTest :
    FunSpec({

        test("custom labels are used for field, Save and Cancel") {
            runComposeUiTest {
                setThemedContent {
                    GuardEditorField(
                        initial = "true",
                        onSave = {},
                        onCancel = {},
                        labels = GuardEditorLabels(field = "Bedingung", save = "Speichern", cancel = "Abbrechen"),
                    )
                }
                onNodeWithText("Bedingung").assertExists()
                onNodeWithText("Speichern").assertExists()
                onNodeWithText("Abbrechen").assertExists()
            }
        }

        test("input beyond MAX_GUARD_LENGTH is cut and a hint is shown") {
            runComposeUiTest {
                var saved: String? = null
                setThemedContent {
                    GuardEditorField(initial = "", onSave = { saved = it }, onCancel = {})
                }
                onNodeWithTag(EditorTestTags.GUARD_INPUT).performTextInput("a".repeat(MAX_GUARD_LENGTH + 50))
                waitForIdle()
                onNodeWithTag(EditorTestTags.GUARD_ERROR).assertTextEquals("Guard limited to $MAX_GUARD_LENGTH characters")
                onNodeWithTag(EditorTestTags.GUARD_INPUT).assertTextContains("a".repeat(MAX_GUARD_LENGTH), substring = true)
                check(saved == null)
            }
        }
    })
