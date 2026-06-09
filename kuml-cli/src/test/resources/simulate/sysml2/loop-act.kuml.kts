@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Infinite-loop ACT fixture for CLI max-steps tests (V2.0.18).
 *
 * Action self-loops forever — used to test that --max-steps terminates
 * execution early with a non-zero exit code.
 */
sysml2Model("LoopAct") {
    val init = initialNode()
    val loop = actionDef("LoopAction", action = "loop()")

    controlFlow("start", init, loop)
    controlFlow("back", loop, loop)  // self-loop: infinite without max-steps

    actDiagram("Loop Activity") {
        include(init)
        include(loop)
    }
}
