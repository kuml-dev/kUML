package dev.kuml.runtime.sysml2

import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import kotlin.time.Duration.Companion.seconds

/** Wave 2D-a: a transition with a trigger AND a timer fires on the event or on the timer expiry, whichever comes first. */
class StmTriggerAndTimerRuntimeTest :
    FunSpec({
        fun machine() =
            sysml2Model(name = "TT") {
                val init = stateDef(name = "Init", isInitial = true)
                val a = stateDef(name = "A")
                val b = stateDef(name = "B")
                transition(name = "i", source = init, target = a)
                transition(name = "t", source = a, target = b, trigger = "go", timer = after(10.seconds), id = "t")
                stmDiagram(name = "d") {
                    include(init)
                    include(a)
                    include(b)
                }
            }

        test("the trigger event fires immediately and disarms the timer") {
            val h = machine().start()
            h.active() shouldContain "A"
            h.runtime.armedTimers(h.instance).map { it.transitionId } shouldContainExactly listOf("t")
            h.send("go")
            h.active() shouldContain "B"
            h.runtime.armedTimers(h.instance) shouldHaveSize 0
            h.runtime.advance(instance = h.instance, by = 60.seconds).firedTransitionIds shouldHaveSize 0
        }

        test("without the event the timer fires when it is due, and only then") {
            val h = machine().start()
            h.runtime.advance(instance = h.instance, by = 9.seconds).firedTransitionIds shouldHaveSize 0
            h.active() shouldNotContain "B"
            h.runtime.advance(instance = h.instance, by = 1.seconds).firedTransitionIds shouldContainExactly listOf("t")
            h.active() shouldContain "B"
            h.runtime.armedTimers(h.instance) shouldHaveSize 0
        }
    })
