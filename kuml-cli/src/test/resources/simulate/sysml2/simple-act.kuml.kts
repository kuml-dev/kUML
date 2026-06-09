@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Minimal SysML 2 ACT fixture for CLI smoke tests (V2.0.18).
 *
 * Linear workflow: Initial → DoWork (Action) → Final.
 * Used by SimulateCommandActTest to verify that `kuml simulate` correctly
 * routes to the ActivityRuntime for ACT diagrams.
 */
sysml2Model("SimpleAct") {
    val init = initialNode()
    val work = actionDef("DoWork", action = "execute()")
    val fin = finalNode()

    controlFlow("start", init, work)
    controlFlow("done", work, fin)

    actDiagram("Simple Activity") {
        include(init)
        include(work)
        include(fin)
    }
}
