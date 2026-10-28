package dev.kuml.runtime.sysml2

import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Wave A2: structured state machines are simulated; only models the checker rejects are blocked,
 * and the message names the checker code instead of promising a future wave.
 */
class Sysml2StateMachineAdapterStructureTest :
    FunSpec({
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

        test("choice, junction, fork, join, history, regions and nested states are no longer blocked") {
            val m =
                sysml2Model(name = "M") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    val ra = region(owner = p, name = "A")
                    val rb = region(owner = p, name = "B")
                    val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                    val a1 = stateDef(name = "A1", parent = p, region = ra)
                    val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                    val b1 = stateDef(name = "B1", parent = p, region = rb)
                    history(name = "H", parent = p, region = ra)
                    val c = choice(name = "C")
                    val j = junction(name = "J")
                    val z = stateDef(name = "Z")
                    transition(name = "tz", source = j, target = z)
                    transition(name = "t0", source = init, target = p)
                    transition(name = "ta", source = ia, target = a1)
                    transition(name = "tb", source = ib, target = b1)
                    transition(name = "tc", source = p, target = c, trigger = "x")
                    transition(name = "tj", source = c, target = j)
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                        include(c)
                        include(j)
                        include(z)
                    }
                }
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            Sysml2StateMachineAdapter.simulationBlockReason(model = m, diagram = d).shouldBeNull()
            val sm = Sysml2StateMachineAdapter.toUmlStateMachine(model = m, diagram = d)
            sm.vertices.map { it.id } shouldBe listOf("Init", "P", "C", "J", "Z")
        }

        test("a transition across regions without fork/join is rejected with the checker code") {
            val m =
                sysml2Model(name = "M") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    val ra = region(owner = p, name = "A")
                    val rb = region(owner = p, name = "B")
                    val a1 = stateDef(name = "A1", parent = p, region = ra)
                    val b1 = stateDef(name = "B1", parent = p, region = rb)
                    transition(name = "t0", source = init, target = p)
                    transition(name = "cross", source = a1, target = b1, trigger = "go", id = "cross")
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                    }
                }
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            val reason = Sysml2StateMachineAdapter.simulationBlockReason(model = m, diagram = d).shouldNotBeNull()
            reason shouldContain "113"
            reason shouldNotContain "wave A2"
            val ex = shouldThrow<IllegalStateException> { Sysml2StateMachineAdapter.toUmlStateMachine(model = m, diagram = d) }
            ex.message!! shouldContain "113"
        }
    })
