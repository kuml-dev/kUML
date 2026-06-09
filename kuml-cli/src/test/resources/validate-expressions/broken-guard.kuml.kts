@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

// V2.0.20a — fixture with a deliberately unparseable guard expression.
// The '@@@' guard cannot be parsed by OclLikeExpressionParser and must
// trigger a non-zero exit from `kuml validate-expressions`.
sysml2Model("BrokenGuard") {
    val initial = stateDef("Initial", isInitial = true)
    val stateA = stateDef("StateA")
    val stateB = stateDef("StateB", isFinal = true)

    transition("init", initial, stateA)
    transition(
        "broken",
        stateA,
        stateB,
        trigger = "go",
        guard = "@@@",
    )

    stmDiagram("BrokenGuard") {
        include(initial)
        include(stateA)
        include(stateB)
    }
}
