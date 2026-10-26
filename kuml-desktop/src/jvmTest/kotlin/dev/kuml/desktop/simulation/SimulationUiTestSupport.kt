package dev.kuml.desktop.simulation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** Machine `init -> A`, `A --go[event.amount > 5]--> B`, `A --alt--> C`, `B --back--> A`. */
internal val GUARDED_UML_SCRIPT =
    """
    stateDiagram(name = "Guarded") {
        val init = initialState()
        val a = state(name = "A")
        val b = state(name = "B")
        val c = state(name = "C")
        transition(source = init, target = a)
        transition(source = a, target = b) { trigger = "go()"; guard = "event.amount > 5" }
        transition(source = a, target = c) { trigger = "alt()" }
        transition(source = b, target = a) { trigger = "back()" }
    }
    """.trimIndent()

/** Same shape, but the guard reads a variable that nobody sets yet. */
internal val VARS_UML_SCRIPT =
    """
    stateDiagram(name = "Vars") {
        val init = initialState()
        val a = state(name = "A")
        val b = state(name = "B")
        val c = state(name = "C")
        transition(source = init, target = a)
        transition(source = a, target = b) { trigger = "go()"; guard = "vars.ready == true" }
        transition(source = a, target = c) { trigger = "alt()" }
    }
    """.trimIndent()

internal fun startTestSession(script: String): SimulationSession =
    (SimulationSession.start(script = script, themeName = "plain") as SimulationStartResult.Started).session

internal fun SimulationSession.transitionIdFor(trigger: String): String = stateMachine.transitions.first { it.trigger == "$trigger()" }.id

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setThemedContent(content: @Composable () -> Unit) {
    setContent { MaterialTheme { content() } }
}
