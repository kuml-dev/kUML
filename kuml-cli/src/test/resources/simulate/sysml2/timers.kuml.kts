@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model
import kotlin.time.Duration.Companion.seconds

// A2 — traffic light driven by timers (after) and a prose guard.
sysml2Model(name = "TimerLight") {
    val initial = stateDef(name = "Initial", isInitial = true)
    val red = stateDef(name = "Red")
    val green = stateDef(name = "Green")
    val done = stateDef(name = "Done", isFinal = true)

    transition(name = "init", source = initial, target = red)
    transition(name = "redToGreen", source = red, target = green, timer = after(duration = 5.seconds), id = "redToGreen")
    transition(
        name = "greenToDone",
        source = green,
        target = done,
        trigger = "stop",
        guard = prose(text = "Operator confirms the stop"),
        id = "greenToDone",
    )

    stmDiagram(name = "TimerLight — phases") {
        include(initial)
        include(red)
        include(green)
        include(done)
    }
}
