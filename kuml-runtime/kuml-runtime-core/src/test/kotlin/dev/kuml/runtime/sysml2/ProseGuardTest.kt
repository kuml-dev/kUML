package dev.kuml.runtime.sysml2

import dev.kuml.runtime.ProseGuardPolicy
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ProseGuardTest :
    FunSpec({
        fun model(): Sysml2Model =
            sysml2Model(name = "P") {
                val init = stateDef(name = "Init", isInitial = true)
                val a = stateDef(name = "A")
                val b = stateDef(name = "B")
                transition(name = "i", source = init, target = a)
                transition(name = "go", source = a, target = b, trigger = "go", guard = prose("The door is open"), id = "go")
                stmDiagram(name = "d") {
                    include(init)
                    include(a)
                    include(b)
                }
            }

        test("block: a prose guard is false and recorded as not evaluable") {
            val h = model().start(prose = ProseGuardPolicy.Block)
            h.send("go")
            h.active() shouldContain "A"
            val e =
                h.instance.trace
                    .filterIsInstance<TraceEntry.GuardNotEvaluable>()
                    .single()
            e.transitionId shouldBe "go"
            e.message shouldBe "prose guard not evaluable"
        }

        test("allow: a prose guard counts as true") {
            val h = model().start(prose = ProseGuardPolicy.Allow)
            h.send("go")
            h.active() shouldContain "B"
        }

        test("ask: the callback decides per transition") {
            val asked = mutableListOf<String>()
            val no =
                model().start(
                    prose =
                        ProseGuardPolicy.Ask {
                            asked += it.id
                            false
                        },
                )
            no.send("go")
            no.active() shouldNotContain "B"
            val yes =
                model().start(
                    prose =
                        ProseGuardPolicy.Ask {
                            asked += it.id
                            true
                        },
                )
            yes.send("go")
            yes.active() shouldContain "B"
            asked shouldBe listOf("go", "go")
        }
    })
