@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

// V2.0.20a — fixture with a deliberately unparseable guard expression.
// The '@@@' guard cannot be parsed by OclLikeExpressionParser and must
// trigger a non-zero exit from `kuml validate-expressions`.
sysml2Model(name = "BrokenGuard") {
    val initial = stateDef(name = "Initial", isInitial = true)
    val stateA = stateDef(name = "StateA")
    val stateB = stateDef(name = "StateB", isFinal = true)

    transition(name = "init", source = initial, target = stateA)
    transition(
        name = "broken",
        source = stateA,
        target = stateB,
        trigger = "go",
        guard = "@@@",
    )

    stmDiagram(name = "BrokenGuard") {
        include(initial)
        include(stateA)
        include(stateB)
    }
}
