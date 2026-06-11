package dev.kuml.widget.compose.demo

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.widget.compose.BehaviourWidget
import dev.kuml.widget.compose.BehaviourWidgetState

/**
 * Demo application showcasing the [BehaviourWidget] with a traffic-light state machine.
 *
 * Traffic-light: Red → Green → Yellow → Red (cycle).
 * Events: "next" advances to the next colour.
 */
public fun main() {
    val model = buildTrafficLightMachine()
    val runtime = StateMachineRuntime()
    val widgetState = BehaviourWidgetState(model = model, runtime = runtime)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "kUML Behaviour Widget Demo — Traffic Light",
            state = rememberWindowState(width = 900.dp, height = 640.dp),
        ) {
            BehaviourWidget(state = widgetState)
        }
    }
}

/**
 * Builds a simple traffic-light [UmlStateMachine]:
 * ```
 * INITIAL → Red --"next"--> Green --"next"--> Yellow --"next"--> Red (loop)
 * ```
 */
private fun buildTrafficLightMachine(): UmlStateMachine {
    val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
    val red = UmlState(id = "Red", name = "Red")
    val green = UmlState(id = "Green", name = "Green")
    val yellow = UmlState(id = "Yellow", name = "Yellow")

    return UmlStateMachine(
        id = "traffic-light",
        name = "Traffic Light",
        vertices = listOf(initial, red, green, yellow),
        transitions = listOf(
            UmlTransition(id = "t-init-red", sourceId = "init", targetId = "Red"),
            UmlTransition(id = "t-red-green", sourceId = "Red", targetId = "Green", trigger = "next"),
            UmlTransition(id = "t-green-yellow", sourceId = "Green", targetId = "Yellow", trigger = "next"),
            UmlTransition(id = "t-yellow-red", sourceId = "Yellow", targetId = "Red", trigger = "next"),
        ),
    )
}
