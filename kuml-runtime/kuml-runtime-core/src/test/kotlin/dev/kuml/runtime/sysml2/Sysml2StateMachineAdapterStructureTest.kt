package dev.kuml.runtime.sysml2

import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.Sysml2ModelBuilder
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Wave A1: structured state machines are modelled but not simulated; the adapter must say so. */
class Sysml2StateMachineAdapterStructureTest :
    FunSpec({
        fun fails(
            expected: String,
            included: String,
            setup: Sysml2ModelBuilder.() -> Unit,
        ) {
            val m =
                sysml2Model(name = "M") {
                    stateDef(name = "Init", isInitial = true)
                    setup()
                    stmDiagram(name = "d") {
                        includeById("Init")
                        includeById(included)
                    }
                }
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            val ex = shouldThrow<IllegalStateException> { Sysml2StateMachineAdapter.toUmlStateMachine(model = m, diagram = d) }
            ex.message!! shouldContain expected
            ex.message!! shouldContain "wave A2"
            Sysml2StateMachineAdapter.simulationBlockReason(model = m, diagram = d).shouldNotBeNull()
        }

        test("flat machine is unaffected") {
            val m =
                sysml2Model(name = "M") {
                    val i = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    transition(name = "t", source = i, target = a)
                    stmDiagram(name = "d") {
                        include(i)
                        include(a)
                    }
                }
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            Sysml2StateMachineAdapter.simulationBlockReason(model = m, diagram = d).shouldBeNull()
            Sysml2StateMachineAdapter.toUmlStateMachine(model = m, diagram = d).vertices.size shouldBe 2
        }

        test("choice") { fails("a choice pseudo-state ('C')", "C") { choice(name = "C") } }
        test("junction") { fails("a junction pseudo-state ('J')", "J") { junction(name = "J") } }
        test("fork") { fails("a fork pseudo-state ('F')", "F") { fork(name = "F") } }
        test("join") { fails("a join pseudo-state ('J')", "J") { join(name = "J") } }
        test(
            "shallow history",
        ) { fails("a shallow history pseudo-state ('H')", "P") { history(name = "H", parent = stateDef(name = "P")) } }
        test(
            "deep history",
        ) { fails("a deep history pseudo-state ('H')", "P") { history(name = "H", deep = true, parent = stateDef(name = "P")) } }
        test("regions") { fails("orthogonal regions ('P')", "P") { region(owner = stateDef(name = "P"), name = "A") } }
        test("nested child") { fails("a nested (composite) state ('K')", "K") { stateDef(name = "K", parent = stateDef(name = "P")) } }
    })
