package dev.kuml.widget.compose

import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for [BehaviourWidgetState].
 *
 * Uses a simple traffic-light machine: INITIAL → Red --"next"--> Green --"next"--> Yellow --"next"--> Red.
 */
class BehaviourWidgetStateTest :
    FunSpec({

        fun buildTrafficLight(): UmlStateMachine {
            val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
            val red = UmlState(id = "Red", name = "Red")
            val green = UmlState(id = "Green", name = "Green")
            val yellow = UmlState(id = "Yellow", name = "Yellow")
            return UmlStateMachine(
                id = "traffic-light",
                name = "Traffic Light",
                vertices = listOf(initial, red, green, yellow),
                transitions =
                    listOf(
                        UmlTransition(id = "t-init-red", sourceId = "init", targetId = "Red"),
                        UmlTransition(id = "t-red-green", sourceId = "Red", targetId = "Green", trigger = "next"),
                        UmlTransition(id = "t-green-yellow", sourceId = "Green", targetId = "Yellow", trigger = "next"),
                        UmlTransition(id = "t-yellow-red", sourceId = "Yellow", targetId = "Red", trigger = "next"),
                    ),
            )
        }

        fun buildState(): BehaviourWidgetState {
            val model = buildTrafficLight()
            val runtime = StateMachineRuntime(guards = OclGuardEvaluator())
            return BehaviourWidgetState(initialModel = model, runtime = runtime)
        }

        test("initial state contains at least one active vertex") {
            val state = buildState()
            state.currentHighlightIds().shouldNotBe(emptySet<String>())
            state.currentHighlightIds().size shouldBeGreaterThan 0
        }

        test("initial active state is Red") {
            val state = buildState()
            state.currentHighlightIds() shouldContain "Red"
        }

        test("sendEvent advances trace") {
            val state = buildState()
            val traceSizeBefore = state.trace.size
            state.sendEvent("next")
            state.trace.size shouldBeGreaterThan traceSizeBefore
        }

        test("sendEvent changes active vertex") {
            val state = buildState()
            state.currentHighlightIds() shouldContain "Red"
            state.sendEvent("next")
            state.currentHighlightIds() shouldContain "Green"
            state.currentHighlightIds() shouldNotContain "Red"
        }

        test("reset clears trace to initial") {
            val state = buildState()
            val initialTraceSize = state.trace.size
            state.sendEvent("next")
            state.sendEvent("next")
            // After two events trace should be larger
            state.trace.size shouldBeGreaterThan initialTraceSize
            state.reset()
            // After reset, trace should be back to initial size (the startup entries)
            state.trace.size shouldBe initialTraceSize
            // tracePosition is at live end after reset (no scrubbing)
            state.tracePosition shouldBe state.trace.size
            state.isScrubbing.shouldBeFalse()
        }

        test("reset restores initial active vertex") {
            val state = buildState()
            state.sendEvent("next") // Red → Green
            state.currentHighlightIds() shouldContain "Green"
            state.reset()
            state.currentHighlightIds() shouldContain "Red"
        }

        test("scrubTo(0) returns initial active vertices") {
            val state = buildState()
            state.sendEvent("next") // advance
            state.scrubTo(0)
            // At position 0 the initial active set is used
            val atZero = state.currentHighlightIds()
            // The initial snapshot had Red as active
            atZero shouldContain "Red"
        }

        test("scrubTo clamps negative to 0") {
            val state = buildState()
            state.scrubTo(-10)
            state.tracePosition shouldBe 0
        }

        test("scrubTo clamps over-trace to trace.size") {
            val state = buildState()
            state.sendEvent("next")
            val traceSize = state.trace.size
            state.scrubTo(traceSize + 100)
            state.tracePosition shouldBe traceSize
        }

        test("isScrubbing is false at trace.size") {
            val state = buildState()
            state.sendEvent("next")
            state.scrubTo(state.trace.size)
            state.isScrubbing.shouldBeFalse()
        }

        test("isScrubbing is true below trace.size") {
            val state = buildState()
            state.sendEvent("next")
            val traceSize = state.trace.size
            if (traceSize > 0) {
                state.scrubTo(0)
                state.isScrubbing.shouldBeTrue()
            }
        }

        test("currentHighlightIds changes after sendEvent") {
            val state = buildState()
            val before = state.currentHighlightIds().toSet()
            state.sendEvent("next")
            val after = state.currentHighlightIds().toSet()
            // Active state changed from Red to Green
            after shouldNotBe before
        }

        test("sendEvent after scrubbing forks and resumes live") {
            val state = buildState()
            state.sendEvent("next") // Red → Green
            state.sendEvent("next") // Green → Yellow
            val traceAfterTwo = state.trace.size

            // Scrub back to the beginning
            state.scrubTo(0)
            state.isScrubbing.shouldBeTrue()

            // Now send an event — this forks from position 0
            state.sendEvent("next")
            // After fork+event, should be live (no longer scrubbing)
            state.isScrubbing.shouldBeFalse()
            // The trace should have been reset to the forked branch
            state.trace.size shouldBeGreaterThan 0
        }
    })
