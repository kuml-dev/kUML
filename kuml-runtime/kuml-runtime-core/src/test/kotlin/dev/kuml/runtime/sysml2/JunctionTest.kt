package dev.kuml.runtime.sysml2

import dev.kuml.runtime.StepResult
import dev.kuml.runtime.StmLimitExceededException
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class JunctionTest :
    FunSpec({
        fun chain(x: Int): Sysml2RuntimeHandle {
            val m =
                sysml2Model(name = "J") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val c = stateDef(name = "C")
                    val j = junction(name = "J1")
                    transition(name = "i", source = init, target = a)
                    transition(name = "in", source = a, target = j, trigger = "go", effect = "first", id = "in")
                    transition(name = "big", source = j, target = b, guard = "vars.x > 1", effect = "second", id = "big")
                    transition(name = "small", source = j, target = c, guard = "vars.x <= 1", id = "small")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                        include(c)
                        include(j)
                    }
                }
            val h = m.start()
            h.instance.variables["x"] = x
            return h
        }

        test("a junction chain picks the first guard-enabled branch and runs all effects") {
            val h = chain(5)
            h.send("go").shouldBeInstanceOf<StepResult.Transitioned>()
            h.active() shouldContain "B"
            h.active() shouldNotContain "J1"
            val fired =
                h.instance.trace
                    .filterIsInstance<TraceEntry.TransitionFired>()
                    .map { it.transitionId }
            fired.takeLast(2) shouldBe listOf("in", "big")
        }

        test("the other branch is taken when its guard holds") {
            val h = chain(0)
            h.send("go")
            h.active() shouldContain "C"
        }

        test("with no valid guard path nothing fires and nothing is left") {
            val m =
                sysml2Model(name = "J") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val j = junction(name = "J1")
                    transition(name = "i", source = init, target = a)
                    transition(name = "in", source = a, target = j, trigger = "go", id = "in")
                    transition(name = "never", source = j, target = b, guard = "vars.x > 100", id = "never")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                        include(j)
                    }
                }
            val h = m.start()
            h.instance.variables["x"] = 1
            h.send("go")
            h.active() shouldContain "A"
            val nf =
                h.instance.trace
                    .filterIsInstance<TraceEntry.NotFired>()
                    .single()
            nf.transitionId shouldBe "in"
            nf.reason shouldContain "junction 'J1'"
        }

        test("a cycle through junctions has no path") {
            val m =
                sysml2Model(name = "J") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val j1 = junction(name = "J1")
                    val j2 = junction(name = "J2")
                    transition(name = "i", source = init, target = a)
                    transition(name = "in", source = a, target = j1, trigger = "go", id = "in")
                    transition(name = "c1", source = j1, target = j2)
                    transition(name = "c2", source = j2, target = j1)
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(j1)
                        include(j2)
                    }
                }
            val h = m.start()
            h.send("go")
            h.active() shouldContain "A"
            h.instance.trace
                .filterIsInstance<TraceEntry.NotFired>()
                .size shouldBe 1
        }

        test("a chain of 33 junctions exceeds the chain limit") {
            val m =
                sysml2Model(name = "J") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val z = stateDef(name = "Z")
                    val js = (1..33).map { junction(name = "J$it") }
                    transition(name = "i", source = init, target = a)
                    transition(name = "in", source = a, target = js.first(), trigger = "go", id = "in")
                    for (k in 0 until js.size - 1) transition(name = "c$k", source = js[k], target = js[k + 1])
                    transition(name = "out", source = js.last(), target = z)
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(z)
                        js.forEach { include(it) }
                    }
                }
            val h = m.start()
            val r = h.send("go")
            r
                .shouldBeInstanceOf<StepResult.Error>()
                .cause
                .shouldBeInstanceOf<StmLimitExceededException>()
                .limit shouldBe "maxJunctionChain"
            h.active() shouldContain "A"
        }
    })
