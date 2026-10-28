package dev.kuml.runtime.sysml2

import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimerSemanticsTest :
    FunSpec({
        fun lights() =
            sysml2Model(name = "L") {
                val init = stateDef(name = "Init", isInitial = true)
                val red = stateDef(name = "Red")
                val green = stateDef(name = "Green")
                val off = stateDef(name = "Off")
                transition(name = "i", source = init, target = red)
                transition(name = "r2g", source = red, target = green, timer = after(5.seconds), id = "r2g")
                transition(name = "g2r", source = green, target = red, timer = after(10.seconds), id = "g2r")
                transition(name = "kill", source = red, target = off, trigger = "kill")
                stmDiagram(name = "d") {
                    include(init)
                    include(red)
                    include(green)
                    include(off)
                }
            }

        test("an after timer fires exactly when it is due") {
            val h = lights().start()
            h.active() shouldContain "Red"
            h.runtime.armedTimers(h.instance).map { it.transitionId } shouldContainExactly listOf("r2g")

            h.runtime.advance(instance = h.instance, by = 4999.milliseconds).firedTransitionIds shouldHaveSize 0
            h.active() shouldContain "Red"
            val r = h.runtime.advance(instance = h.instance, by = 1.milliseconds)
            r.firedTransitionIds shouldContainExactly listOf("r2g")
            h.active() shouldContain "Green"
            h.instance.trace
                .filterIsInstance<TraceEntry.TimerFired>()
                .single()
                .transitionId shouldBe "r2g"
        }

        test("the virtual clock stamps entries with the due time and starts with a ClockStarted entry") {
            val h = lights().start()
            val first = h.instance.trace.first()
            (first is TraceEntry.ClockStarted) shouldBe true
            (first as TraceEntry.ClockStarted).startTime shouldBe "2000-01-01T00:00:00Z"
            h.runtime.advance(instance = h.instance, by = 7.seconds) // fires at t=5; entries carry the due time
            h.instance.trace
                .last()
                .timestamp shouldBe "2000-01-01T00:00:05Z"
        }

        test("leaving the state before the timer is due discards it") {
            val h = lights().start()
            h.send("kill")
            h.runtime.armedTimers(h.instance) shouldHaveSize 0
            h.runtime.advance(instance = h.instance, by = 60.seconds).firedTransitionIds shouldHaveSize 0
            h.active() shouldContain "Off"
        }

        test("entering a state re-arms its timers relative to the entry time") {
            val h = lights().start()
            h.runtime.advance(instance = h.instance, by = 5.seconds) // -> Green at t=5
            h.runtime.advance(instance = h.instance, by = 9.seconds)
            h.active() shouldContain "Green"
            h.runtime.advance(instance = h.instance, by = 1.seconds) // Green due at t=15
            h.active() shouldContain "Red"
            h.runtime.nextTimerDue(h.instance) shouldBe Instant.parse("2000-01-01T00:00:20Z")
        }

        test("advanceToNextTimer jumps to the next due time and fires it") {
            val h = lights().start()
            val r = h.runtime.advanceToNextTimer(h.instance)
            r.firedTransitionIds shouldContainExactly listOf("r2g")
            r.now shouldBe Instant.parse("2000-01-01T00:00:05Z")
        }

        test("an at timer in the past fires exactly once on the next advance") {
            val m =
                sysml2Model(name = "A") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "i", source = init, target = a)
                    transition(name = "late", source = a, target = b, timer = at("2026-01-01T08:00:00Z"), id = "late")
                    transition(name = "back", source = b, target = a, trigger = "back")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val h = m.start(startTime = Instant.parse("2026-06-01T00:00:00Z"))
            h.runtime.advance(instance = h.instance, by = 0.milliseconds).firedTransitionIds shouldContainExactly listOf("late")
            h.active() shouldContain "B"
            h.send("back")
            // re-entering A arms the same past instant again: due immediately, fires once per advance call
            h.runtime.advance(instance = h.instance, by = 0.milliseconds).firedTransitionIds shouldContainExactly listOf("late")
        }

        test("an at timer in the future waits for its instant") {
            val m =
                sysml2Model(name = "A") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "i", source = init, target = a)
                    transition(name = "t", source = a, target = b, timer = at("2026-01-01T08:00:00Z"), id = "t")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val h = m.start(startTime = Instant.parse("2026-01-01T07:59:00Z"))
            h.runtime.advance(instance = h.instance, by = 59.seconds)
            h.active() shouldNotContain "B"
            h.runtime.advance(instance = h.instance, by = 1.seconds)
            h.active() shouldContain "B"
        }

        test("equal due times fire by declaration order of the transitions; the second one is disarmed") {
            val m =
                sysml2Model(name = "A") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val c = stateDef(name = "C")
                    transition(name = "i", source = init, target = a)
                    transition(name = "first", source = a, target = b, timer = after(5.seconds), id = "first")
                    transition(name = "second", source = a, target = c, timer = after(5.seconds), id = "second")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val h = m.start()
            h.runtime.advance(instance = h.instance, by = 5.seconds).firedTransitionIds shouldContainExactly listOf("first")
            h.active() shouldContain "B"
            h.active() shouldNotContain "C"
        }

        test("a machine without timers and without start time keeps the wall clock (no ClockStarted)") {
            val m =
                sysml2Model(name = "F") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    transition(name = "i", source = init, target = a)
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                    }
                }
            val h = m.start()
            h.instance.trace.none { it is TraceEntry.ClockStarted } shouldBe true
        }
    })
