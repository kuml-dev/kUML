package dev.kuml.runtime.sysml2

import dev.kuml.runtime.StmLimitExceededException
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimerLimitsTest :
    FunSpec({
        test("1025 timers armed at once abort with a limit exception naming the state") {
            val m =
                sysml2Model(name = "Many") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "i", source = init, target = a)
                    for (i in 1..1025) {
                        transition(name = "t$i", source = a, target = b, timer = after(i.seconds), id = "t$i")
                    }
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val ex = shouldThrow<StmLimitExceededException> { m.start() }
            ex.limit shouldBe "maxArmedTimers"
            ex.message!! shouldContain "'A'"
        }

        test("a limit hit inside a timer firing rolls the instance back before the exception propagates") {
            val m =
                sysml2Model(name = "Rollback") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val c = stateDef(name = "C")
                    transition(name = "i", source = init, target = a)
                    transition(name = "go", source = a, target = b, timer = after(5.seconds), id = "go")
                    for (i in 1..1025) {
                        transition(name = "t$i", source = b, target = c, timer = after(i.seconds), id = "t$i")
                    }
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val h = m.start()
            val before = h.active()
            val timersBefore = h.runtime.armedTimers(h.instance).map { it.transitionId }
            val traceBefore = h.instance.trace.size
            val ex = shouldThrow<StmLimitExceededException> { h.runtime.advance(instance = h.instance, by = 5.seconds) }
            ex.limit shouldBe "maxArmedTimers"
            h.active() shouldBe before
            // the failed timer itself is consumed
            h.runtime.armedTimers(h.instance).map { it.transitionId } shouldBe timersBefore.filter { it != "go" }
            h.instance.trace.size shouldBe traceBefore
        }

        test("a self-loop with a 1 ms timer stops at the firing limit instead of hanging") {
            val m =
                sysml2Model(name = "Loop") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    transition(name = "i", source = init, target = a)
                    transition(name = "tick", source = a, target = a, timer = after(1.milliseconds), id = "tick")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                    }
                }
            val h = m.start()
            val ex = shouldThrow<StmLimitExceededException> { h.runtime.advance(instance = h.instance, by = 3650.days) }
            ex.limit shouldBe "maxFiringsPerAdvance"
        }

        test("an advance beyond the horizon is rejected") {
            val m =
                sysml2Model(name = "H") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    transition(name = "i", source = init, target = a)
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                    }
                }
            val h = m.start(startTime = java.time.Instant.parse("2026-01-01T00:00:00Z"))
            val ex = shouldThrow<StmLimitExceededException> { h.runtime.advance(instance = h.instance, by = 3651.days) }
            ex.limit shouldBe "maxAdvanceHorizon"
        }
    })
