package dev.kuml.runtime.sysml2

import dev.kuml.runtime.StepResult
import dev.kuml.runtime.StmLimitExceededException
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RegionSemanticsTest :
    FunSpec({
        fun twoRegions() =
            sysml2Model(name = "R") {
                val init = stateDef(name = "Init", isInitial = true)
                val p = stateDef(name = "P")
                val ra = region(owner = p, name = "A")
                val rb = region(owner = p, name = "B")
                val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                val a1 = stateDef(name = "A1", parent = p, region = ra)
                val a2 = stateDef(name = "A2", parent = p, region = ra)
                val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                val b1 = stateDef(name = "B1", parent = p, region = rb)
                val b2 = stateDef(name = "B2", parent = p, region = rb)
                val stopped = stateDef(name = "Stopped")
                transition(name = "t0", source = init, target = p)
                transition(name = "ia", source = ia, target = a1)
                transition(name = "ib", source = ib, target = b1)
                transition(name = "a", source = a1, target = a2, trigger = "e", id = "tA")
                transition(name = "b", source = b1, target = b2, trigger = "e", id = "tB")
                transition(name = "stop", source = p, target = stopped, trigger = "stop", id = "tStop")
                stmDiagram(name = "d") {
                    include(init)
                    include(p)
                    include(stopped)
                }
            }

        test("entering a composite enters the initial state of every region") {
            val h = twoRegions().start()
            h.active() shouldContainAll listOf("P", "A1", "B1")
        }

        test("one event moves the active state of every region") {
            val h = twoRegions().start()
            val r = h.send("e")
            r.shouldBeInstanceOf<StepResult.Transitioned>().transitionIds shouldBe listOf("tA", "tB")
            h.active() shouldContainAll listOf("P", "A2", "B2")
            h.active() shouldNotContain "A1"
        }

        test("a transition inside one region leaves the other region untouched") {
            val m =
                sysml2Model(name = "R") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    val ra = region(owner = p, name = "A")
                    val rb = region(owner = p, name = "B")
                    val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                    val a1 = stateDef(name = "A1", parent = p, region = ra)
                    val a2 = stateDef(name = "A2", parent = p, region = ra)
                    val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                    val b1 = stateDef(name = "B1", parent = p, region = rb)
                    transition(name = "t0", source = init, target = p)
                    transition(name = "ia", source = ia, target = a1)
                    transition(name = "ib", source = ib, target = b1)
                    transition(name = "a", source = a1, target = a2, trigger = "e")
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                    }
                }
            val h = m.start()
            h.send("e")
            h.active() shouldContainAll listOf("P", "A2", "B1")
            h.instance.trace
                .filterIsInstance<TraceEntry.StateExited>()
                .map { it.vertexId } shouldNotContain "B1"
        }

        test("a composite-level transition leaves all regions") {
            val h = twoRegions().start()
            h.send("stop")
            h.active() shouldContain "Stopped"
            h.active() shouldNotContain "A1"
            h.active() shouldNotContain "B1"
            h.active() shouldNotContain "P"
        }

        test("an inner transition pre-empts a composite-level one for the same event") {
            val m =
                sysml2Model(name = "R") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    val ra = region(owner = p, name = "A")
                    val rb = region(owner = p, name = "B")
                    val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                    val a1 = stateDef(name = "A1", parent = p, region = ra)
                    val a2 = stateDef(name = "A2", parent = p, region = ra)
                    val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                    val b1 = stateDef(name = "B1", parent = p, region = rb)
                    val out = stateDef(name = "Out")
                    transition(name = "t0", source = init, target = p)
                    transition(name = "ia", source = ia, target = a1)
                    transition(name = "ib", source = ib, target = b1)
                    transition(name = "inner", source = a1, target = a2, trigger = "e", id = "inner")
                    transition(name = "outer", source = p, target = out, trigger = "e", id = "outer")
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                        include(out)
                    }
                }
            val h = m.start()
            h.send("e")
            h.active() shouldContainAll listOf("P", "A2", "B1")
            h.active() shouldNotContain "Out"
            val pre =
                h.instance.trace
                    .filterIsInstance<TraceEntry.Preempted>()
                    .single()
            pre.transitionId shouldBe "outer"
            pre.byTransitionId shouldBe "inner"
        }

        test("more than 64 concurrently active leaves abort with a limit exception") {
            val m =
                sysml2Model(name = "Wide") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    transition(name = "t0", source = init, target = p)
                    // 16 regions x 5 chained composites is overkill; 16 regions with 5 orthogonal sub-composites each.
                    for (i in 1..16) {
                        val r = region(owner = p, name = "R$i")
                        val ini = stateDef(name = "I$i", isInitial = true, parent = p, region = r)
                        val q = stateDef(name = "Q$i", parent = p, region = r)
                        transition(name = "q$i", source = ini, target = q)
                        for (j in 1..5) {
                            val sub = region(owner = q, name = "S$j")
                            val si = stateDef(name = "SI${i}_$j", isInitial = true, parent = q, region = sub)
                            val sl = stateDef(name = "SL${i}_$j", parent = q, region = sub)
                            transition(name = "s${i}_$j", source = si, target = sl)
                        }
                    }
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                    }
                }
            val ex = shouldThrow<StmLimitExceededException> { m.start() }
            ex.limit shouldBe "maxActiveLeaves"
        }
    })
