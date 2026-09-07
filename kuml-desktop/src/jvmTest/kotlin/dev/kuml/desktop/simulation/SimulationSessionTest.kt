package dev.kuml.desktop.simulation

import dev.kuml.runtime.StepResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Runs the real [SimulationSession.start] pipeline end-to-end against actual `.kuml.kts`
 * source text (evaluated via [dev.kuml.desktop.render.DesktopRenderPipeline.prepareSimulation]) —
 * headless, no Compose UI, no Swing/Batik canvas involved.
 */
class SimulationSessionTest :
    FunSpec({

        // No entry/exit/effect actions here (unlike the CLI's order-lifecycle.kuml.kts fixture,
        // which uses "validate()"/"reserveStock()"/"log()") — this session ALWAYS runs sandboxed
        // (Spez. F27), and those names aren't in SandboxPolicy.DEFAULT_ALLOWED_FUNCTIONS, which
        // would fail the very first transition's entry action before the happy path even starts.
        val umlScript =
            """
            stateDiagram(name = "OrderLifecycle") {
                val init = initialState()
                val draft = state(name = "Draft")
                val confirmed = state(name = "Confirmed")
                val done = finalState(name = "Done")

                transition(source = init, target = draft)
                transition(source = draft, target = confirmed) { trigger = "confirm()" }
                transition(source = confirmed, target = done) { trigger = "deliver()" }
            }
            """.trimIndent()

        val sysml2Script =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("TrafficLight") {
                val initial = stateDef("Initial", isInitial = true)
                val red = stateDef("Red")
                val green = stateDef("Green")

                transition("init", initial, red)
                transition("redToGreen", red, green, trigger = "next")

                stmDiagram("Phase cycle") {
                    include(initial)
                    include(red)
                    include(green)
                }
            }
            """.trimIndent()

        val classScript =
            """
            classDiagram(name = "NotSimulatable") {
                classOf(name = "A") { }
            }
            """.trimIndent()

        val noInitialScript =
            """
            stateDiagram(name = "NoInitial") {
                state(name = "Lonely")
            }
            """.trimIndent()

        val guardFailureScript =
            """
            stateDiagram(name = "GuardFailure") {
                val init = initialState()
                val a = state(name = "A")
                val b = state(name = "B")
                transition(source = init, target = a)
                transition(source = a, target = b) { trigger = "go()"; guard = "self.thisAttributeDoesNotExist" }
            }
            """.trimIndent()

        // The stateDiagram DSL qualifies vertex IDs as "<stateMachineId>::<name>" (UmlIds.vertex)
        // rather than using the bare display name — look them up by name instead of hardcoding
        // the qualified form, so this test doesn't silently start asserting the wrong thing if
        // the ID scheme ever changes.
        fun SimulationSession.vertexIdNamed(name: String): String = stateMachine.vertices.first { it.name == name }.id

        fun guardThreadCount(): Int = Thread.getAllStackTraces().keys.count { it.name.startsWith("kuml-sandbox-guard-") }

        /** Polls up to ~2s for [guardThreadCount] to settle at [expected] after shutdownNow(). */
        fun awaitGuardThreadCount(expected: Int) {
            val deadline = System.currentTimeMillis() + 2_000
            while (guardThreadCount() != expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
        }

        // ── Happy path ───────────────────────────────────────────────────────

        test("Happy path UML: Started with a hidden ring per vertex and the right event names") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Started>()
            val session = result.session
            try {
                session.baseSvg shouldContain "visibility=\"hidden\""
                session.eventNames shouldBe listOf("confirm", "deliver")
                session.activeVertexIds() shouldContain session.vertexIdNamed("Draft")

                val stepResult = session.send(eventName = "confirm")
                stepResult.shouldBeInstanceOf<StepResult.Transitioned>()
                session.activeVertexIds() shouldContain session.vertexIdNamed("Confirmed")
            } finally {
                session.close()
            }
        }

        test("Happy path SysML-2 STM: Started, vertex IDs from the SVG match the runtime") {
            val result = SimulationSession.start(script = sysml2Script, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Started>()
            val session = result.session
            try {
                session.baseSvg shouldContain "highlight-ring-Initial"
                session.baseSvg shouldContain "highlight-ring-Red"
                session.baseSvg shouldContain "highlight-ring-Green"
                session.eventNames shouldBe listOf("next")
                session.activeVertexIds() shouldContain "Red"
            } finally {
                session.close()
            }
        }

        // ── Unsupported / Failed ─────────────────────────────────────────────

        test("A non-simulatable diagram (class diagram) returns Unsupported") {
            SimulationSession
                .start(script = classScript, themeName = "plain")
                .shouldBeInstanceOf<SimulationStartResult.Unsupported>()
        }

        test("A syntax error returns Failed, not an exception") {
            SimulationSession
                .start(script = "this is not valid kotlin @@@", themeName = "plain")
                .shouldBeInstanceOf<SimulationStartResult.Failed>()
        }

        test("A state machine with no INITIAL pseudostate returns Failed, not a thrown exception, and leaks no thread") {
            val before = guardThreadCount()
            val result = SimulationSession.start(script = noInitialScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Failed>()
            awaitGuardThreadCount(before)
            guardThreadCount() shouldBe before
        }

        // ── Guard failure surfaces as status, not silent Stayed (Spez. F29) ──
        //
        // "self.thisAttributeDoesNotExist" is expected to fail guard evaluation (unknown
        // attribute navigation) rather than cleanly evaluate to false — the exact failure mode
        // (a Failed GuardResult surfacing as GuardFailed, or an uncaught evaluator exception
        // surfacing as StepResult.Error) is an OCL-evaluator implementation detail this desktop
        // wave doesn't own; what matters here is that neither looks like "nothing happened".
        test("a guard that fails to evaluate never surfaces as a silent Stayed") {
            val result = SimulationSession.start(script = guardFailureScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Started>()
            val session = result.session
            try {
                session.send(eventName = "go")
                session.status.shouldNotBeInstanceOf<SimulationStatus.Stayed>()
                session.status.shouldNotBeInstanceOf<SimulationStatus.Idle>()
            } finally {
                session.close()
            }
        }

        // ── close() / leak ───────────────────────────────────────────────────

        test("close() releases the sandbox guard thread pool") {
            val before = guardThreadCount()
            val result = SimulationSession.start(script = guardFailureScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            session.send(eventName = "go") // forces at least one guard evaluation, spawning a pool thread
            session.close()
            awaitGuardThreadCount(before)
            guardThreadCount() shouldBe before
        }

        // ── Discard semantics (Spez. E23) ────────────────────────────────────

        test("sending an event while scrubbing forks and reports DiscardedSteps") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm") // Draft -> Confirmed
                session.send(eventName = "deliver") // Confirmed -> Done
                val liveTraceSize = session.widgetState.trace.size
                session.scrubTo(0) // rewind to the very start of the trace
                session.send(eventName = "confirm") // re-fire from the scrub point — discards everything after it

                session.status.shouldBeInstanceOf<SimulationStatus.DiscardedSteps>()
                (session.status as SimulationStatus.DiscardedSteps).count shouldBe liveTraceSize
                // The new trace is no longer than the old live end would have suggested for two
                // re-applied steps — confirms the fork actually happened, not a silent no-op.
                (session.widgetState.trace.size <= liveTraceSize) shouldBe true
            } finally {
                session.close()
            }
        }

        // ── isTerminated unlocks again after scrubbing away from the final state (review fix) ──

        test("isTerminated becomes false again after scrubbing back from a terminated run, unlocking the fork workflow") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm") // Draft -> Confirmed
                val afterConfirm = session.widgetState.trace.size
                session.send(eventName = "deliver") // Confirmed -> Done
                session.isTerminated shouldBe true

                session.scrubTo(afterConfirm) // back to "Confirmed" — not itself a final state
                session.isTerminated shouldBe false
                session.enabledEvents() shouldContain "deliver"

                // The fork-and-continue workflow (Spez. E23) must actually be reachable again —
                // this is exactly what every Step/event/Auto-Advance button in SimulationBar.kt
                // gates on `!terminated` for.
                val forked = session.send(eventName = "deliver")
                forked.shouldBeInstanceOf<StepResult.Terminated>()
                session.isTerminated shouldBe true
            } finally {
                session.close()
            }
        }

        // ── reset() / scrubTo() / clearDiscardedStepsNotice() ───────────────────

        test("reset returns to Idle, clears lastResult/isTerminated, and rewinds the trace") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                val initialTraceSize = session.widgetState.trace.size
                session.send(eventName = "confirm")
                session.send(eventName = "deliver")
                session.isTerminated shouldBe true

                session.reset()

                session.status shouldBe SimulationStatus.Idle
                session.isTerminated shouldBe false
                session.widgetState.trace.size shouldBe initialTraceSize
                session.activeVertexIds() shouldContain session.vertexIdNamed("Draft")
            } finally {
                session.close()
            }
        }

        test("scrubTo moves the highlighted vertex back without touching lastResult") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm") // Draft -> Confirmed
                val afterConfirm = session.widgetState.trace.size
                session.activeVertexIds() shouldContain session.vertexIdNamed("Confirmed")

                session.scrubTo(0)
                session.activeVertexIds() shouldContain session.vertexIdNamed("Draft")

                session.scrubTo(afterConfirm)
                session.activeVertexIds() shouldContain session.vertexIdNamed("Confirmed")
            } finally {
                session.close()
            }
        }

        test("clearDiscardedStepsNotice resets a DiscardedSteps status back to Idle") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm")
                session.send(eventName = "deliver")
                session.scrubTo(0)
                session.send(eventName = "confirm")
                session.status.shouldBeInstanceOf<SimulationStatus.DiscardedSteps>()

                session.clearDiscardedStepsNotice()

                session.status shouldBe SimulationStatus.Idle
            } finally {
                session.close()
            }
        }

        test("clearDiscardedStepsNotice leaves every other status untouched") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm")
                session.send(eventName = "deliver")
                session.status shouldBe SimulationStatus.Terminated

                session.clearDiscardedStepsNotice()

                session.status shouldBe SimulationStatus.Terminated
            } finally {
                session.close()
            }
        }

        // ── statusFor mapping (Terminated / Error / Stayed / GuardTimeout) ──────

        test("statusFor: reaching the final state reports Terminated") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "confirm")
                session.send(eventName = "deliver")
                session.status shouldBe SimulationStatus.Terminated
            } finally {
                session.close()
            }
        }

        test("statusFor: an event with no matching transition reports Stayed with the runtime's reason") {
            val result = SimulationSession.start(script = umlScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                // "deliver" has no transition out of the initial "Draft" state.
                session.send(eventName = "deliver")
                session.status.shouldBeInstanceOf<SimulationStatus.Stayed>()
            } finally {
                session.close()
            }
        }

        test("statusFor: a guard that fails to evaluate (not a sandbox timeout) reports GuardFailed, not GuardTimeout") {
            val result = SimulationSession.start(script = guardFailureScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            try {
                session.send(eventName = "go")
                session.status.shouldBeInstanceOf<SimulationStatus.GuardFailed>()
            } finally {
                session.close()
            }
        }

        // GuardTimeout (as opposed to GuardFailed) isn't separately covered here:
        // SimulationSession.start hardcodes SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS with no
        // public way to inject a shorter one, so provoking an actual sandbox timeout isn't
        // reachable through this class's public surface. The message-prefix contract that
        // `statusFor` keys off of is instead locked down directly on the producer, see
        // TimeLimitedGuardEvaluatorTest's "slow guard times out" test.
    })
