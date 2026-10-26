package dev.kuml.desktop.simulation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.isMacOs
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compose UI tests of the simulator side panel parts. Asserts semantics and text only, never
 * pixels or fonts (a render reference made on one OS would break another).
 */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class SimulationPanesUiTest :
    FunSpec({
        val en = Strings.EN

        fun androidx.compose.ui.test.ComposeUiTest.awaitText(text: String) {
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }

        // ── Inspector ────────────────────────────────────────────────────────

        test("inspector shows guard status as glyph plus text and explains the events that are not enabled") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            session.payloadDraft = "{\"amount\": 1}"
            try {
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    awaitText("✗ false")
                    onNodeWithText("– no guard").assertExists()
                    onNodeWithTag(SimTestTags.NOT_ENABLED).assertExists()
                    // "go": every guard is false. "back": nothing leaves the active state.
                    onNodeWithText(en.simWhyGuardFalse).assertExists()
                    onNodeWithText(en.simWhyNoTransition.format("A")).assertExists()
                }
            } finally {
                session.close()
            }
        }

        test("a guard that cannot be evaluated without a payload shows an error, like the real step would") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("⚠", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag(SimTestTags.NOT_ENABLED).assertExists()
                }
            } finally {
                session.close()
            }
        }

        test("a payload draft that satisfies the guard turns it true and removes the explanation") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                session.payloadDraft = "{\"amount\": 10}"
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    awaitText("✓ true")
                    onNodeWithText(en.simWhyGuardFalse).assertDoesNotExist()
                    onNodeWithText(en.simDraftPayloadFootnote).assertExists()
                }
            } finally {
                session.close()
            }
        }

        test("clicking a row selects its transition and a second click deselects it") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            session.payloadDraft = "{\"amount\": 1}"
            try {
                val id = session.transitionIdFor("go")
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    awaitText("✗ false")
                    onNodeWithTag(SimTestTags.inspectorRow(id)).performClick()
                    session.selectedTransitionId shouldBe id
                    onNodeWithTag(SimTestTags.inspectorRow(id)).performClick()
                    session.selectedTransitionId shouldBe null
                }
            } finally {
                session.close()
            }
        }

        test("editing a guard raises the temporary band, revert removes it again") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            session.payloadDraft = "{\"amount\": 1}"
            try {
                val id = session.transitionIdFor("go")
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    awaitText("✗ false")
                    onNodeWithTag(SimTestTags.TEMP_BAND).assertDoesNotExist()

                    onNodeWithTag(SimTestTags.editGuard(id)).performClick()
                    onNode(hasSetTextAction()).performTextClearance()
                    onNode(hasSetTextAction()).performTextInput("true")
                    mainClock.advanceTimeBy(400) // past the editor's type-check debounce
                    waitForIdle()
                    onNodeWithText(en.simApply).performClick()
                    waitForIdle()

                    onNodeWithTag(SimTestTags.TEMP_BAND).assertExists()
                    onNodeWithText(en.simTemporaryBand.format(1)).assertExists()
                    onNodeWithText(en.simGuardEdited).assertExists()
                    session.editedGuardCount shouldBe 1

                    onNodeWithTag(SimTestTags.revertGuard(id)).performClick()
                    waitForIdle()
                    onNodeWithTag(SimTestTags.TEMP_BAND).assertDoesNotExist()
                    session.editedGuardCount shouldBe 0
                }
            } finally {
                session.close()
            }
        }

        test("while scrubbing the edit button is disabled") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                val id = session.transitionIdFor("go")
                session.send(eventName = "alt")
                session.scrubTo(0)
                runComposeUiTest {
                    setThemedContent { SimulationInspectorPane(session = session, strings = en) }
                    awaitText(en.simScrubbingFootnote)
                    onNodeWithTag(SimTestTags.editGuard(id)).assertIsNotEnabled()
                }
            } finally {
                session.close()
            }
        }

        // ── Variables ────────────────────────────────────────────────────────

        test("a variable read by a guard but not set is listed as not set") {
            val session = startTestSession(VARS_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { SimulationVariablesPane(session = session, strings = en) }
                    onNodeWithTag(SimTestTags.variableRow("ready")).assertExists()
                    onNodeWithText("ready  (${en.simNotSet})").assertExists()
                }
            } finally {
                session.close()
            }
        }

        test("a number field commits on Enter, shows an error for bad input and Esc discards it") {
            val session = startTestSession(VARS_UML_SCRIPT)
            try {
                session.setVariable(name = "count", value = JsonPrimitive(3))
                runComposeUiTest {
                    setThemedContent { SimulationVariablesPane(session = session, strings = en) }
                    onNodeWithTag(SimTestTags.variableField("count")).performTextClearance()
                    onNodeWithTag(SimTestTags.variableField("count")).performTextInput("abc")
                    onNodeWithTag(SimTestTags.variableField("count")).performKeyInput { pressKey(Key.Enter) }
                    waitForIdle()
                    onNodeWithTag(SimTestTags.variableError("count"), useUnmergedTree = true).assertExists()
                    onNodeWithText(en.simVarErrNotNumber).assertExists()
                    session.widgetState.liveVariables["count"] shouldBe JsonPrimitive(3L)

                    onNodeWithTag(SimTestTags.variableField("count")).performKeyInput { pressKey(Key.Escape) }
                    waitForIdle()
                    onNodeWithText(en.simVarErrNotNumber).assertDoesNotExist()

                    onNodeWithTag(SimTestTags.variableField("count")).performTextClearance()
                    onNodeWithTag(SimTestTags.variableField("count")).performTextInput("7")
                    onNodeWithTag(SimTestTags.variableField("count")).performKeyInput { pressKey(Key.Enter) }
                    waitForIdle()
                    session.widgetState.liveVariables["count"] shouldBe JsonPrimitive(7L)
                }
            } finally {
                session.close()
            }
        }

        test("a boolean variable is a switch with an accessible description that toggles the value") {
            val session = startTestSession(VARS_UML_SCRIPT)
            try {
                session.setVariable(name = "flag", value = JsonPrimitive(true))
                runComposeUiTest {
                    setThemedContent { SimulationVariablesPane(session = session, strings = en) }
                    onNodeWithContentDescription(en.simVarSwitchA11y.format("flag")).assertExists()
                    onNodeWithTag(SimTestTags.variableSwitch("flag")).performClick()
                    waitForIdle()
                    session.widgetState.liveVariables["flag"] shouldBe JsonPrimitive(false)
                }
            } finally {
                session.close()
            }
        }

        test("a variable can be added through the add row, and a bad name is explained") {
            val session = startTestSession(VARS_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { SimulationVariablesPane(session = session, strings = en) }
                    onNode(hasScrollAction()).performScrollToNode(hasTestTag(SimTestTags.ADD_NAME))
                    onNodeWithTag(SimTestTags.ADD_NAME).performTextInput("1bad")
                    onNodeWithTag(SimTestTags.ADD_VALUE).performTextInput("5")
                    onNodeWithTag(SimTestTags.ADD_BUTTON).performClick()
                    waitForIdle()
                    onNodeWithText(en.simVarErrName).assertExists()

                    onNodeWithTag(SimTestTags.ADD_NAME).performTextClearance()
                    onNodeWithTag(SimTestTags.ADD_NAME).performTextInput("good")
                    onNodeWithTag(SimTestTags.ADD_BUTTON).performClick()
                    waitForIdle()
                    session.widgetState.liveVariables["good"] shouldBe JsonPrimitive(5L)
                }
            } finally {
                session.close()
            }
        }

        test("while scrubbing the variables are read-only and a history band explains why") {
            val session = startTestSession(VARS_UML_SCRIPT)
            try {
                session.setVariable(name = "flag", value = JsonPrimitive(true))
                session.send(eventName = "alt")
                session.scrubTo(0)
                runComposeUiTest {
                    setThemedContent { SimulationVariablesPane(session = session, strings = en) }
                    onNodeWithTag(SimTestTags.HISTORY_BAND).assertExists()
                    onNodeWithTag(SimTestTags.variableSwitch("flag")).assertIsNotEnabled()
                    onNodeWithTag(SimTestTags.variableUnset("flag")).assertIsNotEnabled()
                }
            } finally {
                session.close()
            }
        }

        // ── Event row (payload) ──────────────────────────────────────────────

        test("an invalid payload shows an inline error and disables Send, a valid one enables it") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { FreeformEventRow(session = session, strings = en, enabled = true) }
                    onNodeWithTag(SimTestTags.EVENT_FIELD).performTextInput("go")
                    onNodeWithTag(SimTestTags.PAYLOAD_FIELD).performTextInput("{nope")
                    mainClock.advanceTimeBy(PAYLOAD_DEBOUNCE_MS + 50)
                    waitForIdle()
                    onNodeWithTag(SimTestTags.PAYLOAD_ERROR, useUnmergedTree = true).assertExists()
                    onNodeWithTag(SimTestTags.SEND).assertIsNotEnabled()

                    onNodeWithTag(SimTestTags.PAYLOAD_FIELD).performTextClearance()
                    onNodeWithTag(SimTestTags.PAYLOAD_FIELD).performTextInput("{\"amount\": 9}")
                    mainClock.advanceTimeBy(PAYLOAD_DEBOUNCE_MS + 50)
                    waitForIdle()
                    onNodeWithTag(SimTestTags.PAYLOAD_ERROR, useUnmergedTree = true).assertDoesNotExist()
                    onNodeWithTag(SimTestTags.SEND).assertIsEnabled()
                }
            } finally {
                session.close()
            }
        }

        test("the platform command key plus Enter sends the event with its payload") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { FreeformEventRow(session = session, strings = en, enabled = true) }
                    onNodeWithTag(SimTestTags.EVENT_FIELD).performTextInput("go")
                    onNodeWithTag(SimTestTags.PAYLOAD_FIELD).performTextInput("{\"amount\": 9}")
                    val modifier = if (isMacOs()) Key.MetaLeft else Key.CtrlLeft
                    onNodeWithTag(SimTestTags.PAYLOAD_FIELD).performKeyInput { withKeyDown(modifier) { pressKey(Key.Enter) } }
                    waitForIdle()
                    session.activeVertexIds() shouldBe
                        setOf(
                            session.stateMachine.vertices
                                .first { it.name == "B" }
                                .id,
                        )
                }
            } finally {
                session.close()
            }
        }

        // ── Side panel ───────────────────────────────────────────────────────

        test("sections are headings that collapse and expand and say so") {
            val session = startTestSession(GUARDED_UML_SCRIPT)
            try {
                runComposeUiTest {
                    setThemedContent { SimulationSidePanel(session = session, strings = en) }
                    val header = onNodeWithTag(SimTestTags.section("inspector"))
                    header.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                    header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, en.simSectionExpanded))
                    onNodeWithTag(SimTestTags.INSPECTOR).assertExists()

                    header.performClick()
                    waitForIdle()
                    onNodeWithTag(SimTestTags.INSPECTOR).assertDoesNotExist()
                    onNodeWithTag(SimTestTags.section("inspector"))
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, en.simSectionCollapsed))
                    // The other sections stay.
                    onNodeWithTag(SimTestTags.VARIABLES).assertExists()
                    onNode(hasText(en.simTraceTitle)).assertExists()
                }
            } finally {
                session.close()
            }
        }
    })
