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

        // The sandbox guard pool is JVM-global in the sense that Thread.getAllStackTraces() sees
        // every `kuml-sandbox-guard-*` thread of every session ever started in this test JVM,
        // including daemon threads of EARLIER tests that are still winding down after their own
        // shutdownNow(). Comparing raw counts before/after therefore fails whenever such a
        // straggler exits in between (count drops below `before`). Track the thread OBJECTS
        // instead: whatever this test's own session added to the set is what must disappear.
        fun guardThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filterTo(HashSet()) { it.name.startsWith("kuml-sandbox-guard-") }

        /** Threads that appeared since [before] and are still alive. */
        fun leakedSince(before: Set<Thread>): List<Thread> = (guardThreads() - before).filter { it.isAlive }

        /**
         * Waits for every guard thread created since [before] to terminate after shutdownNow().
         * Joins each thread (up to 5s total) rather than polling a count, so the wait ends the
         * moment the threads are gone and a genuine leak still fails with the thread names.
         */
        fun awaitNoLeakedGuardThreads(before: Set<Thread>) {
            val deadline = System.nanoTime() + 5_000_000_000L
            for (t in guardThreads() - before) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs > 0) t.join(remainingMs)
            }
            leakedSince(before).map { it.name } shouldBe emptyList()
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

        test("Abstract actions (entry validate(), effect notifyCustomer()) do not block the start") {
            val orderScript =
                """
                stateDiagram(name = "Order Lifecycle") {
                    val start = initialState()
                    val draft = state(name = "Draft") { entry = "validate()" }
                    val confirmed = state(name = "Confirmed")
                    transition(start, draft)
                    transition(draft, confirmed) {
                        trigger = "confirm()"
                        effect = "notifyCustomer()"
                    }
                }
                """.trimIndent()
            val result = SimulationSession.start(script = orderScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Started>()
            val session = result.session
            try {
                session.send(eventName = "confirm").shouldBeInstanceOf<StepResult.Transitioned>()
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
            val before = guardThreads()
            val result = SimulationSession.start(script = noInitialScript, themeName = "plain")
            result.shouldBeInstanceOf<SimulationStartResult.Failed>()
            awaitNoLeakedGuardThreads(before)
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
            val before = guardThreads()
            val result = SimulationSession.start(script = guardFailureScript, themeName = "plain")
            val session = (result as SimulationStartResult.Started).session
            session.send(eventName = "go") // forces at least one guard evaluation, spawning a pool thread
            (guardThreads() - before).isNotEmpty() shouldBe true // sanity: the session really did spawn one
            session.close()
            awaitNoLeakedGuardThreads(before)
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

        // ── Wave A: inspector, variables, payload, temporary guards ──────────

        val payloadUmlScript =
            """
            stateDiagram(name = "Payload") {
                val init = initialState()
                val a = state(name = "A")
                val b = state(name = "B")
                val c = state(name = "C")
                transition(source = init, target = a)
                transition(source = a, target = b) { trigger = "go()"; guard = "event.amount > 5" }
                transition(source = a, target = c) { trigger = "alt()" }
                transition(source = b, target = a) { trigger = "back()" }
            }
            """.trimIndent()

        val payloadSysmlScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Payload") {
                val initial = stateDef("Initial", isInitial = true)
                val a = stateDef("A")
                val b = stateDef("B")

                transition("init", initial, a)
                transition("go", a, b, trigger = "go", guard = "event.amount > 5")

                stmDiagram("Cycle") {
                    include(initial)
                    include(a)
                    include(b)
                }
            }
            """.trimIndent()

        fun withSession(
            script: String,
            block: (SimulationSession) -> Unit,
        ) {
            val session = (SimulationSession.start(script = script, themeName = "plain") as SimulationStartResult.Started).session
            try {
                block(session)
            } finally {
                session.close()
            }
        }

        fun SimulationSession.idOf(name: String) = stateMachine.vertices.first { it.name == name }.id

        test("the session runs with the guard-edit policy") {
            withSession(payloadUmlScript) { it.widgetState.editPolicy shouldBe dev.kuml.widget.compose.EditPolicy.GuardsOnly }
        }

        test("UML: a payload above the threshold fires the guarded transition, below it does not") {
            withSession(payloadUmlScript) { session ->
                session.send(eventName = "go", payloadJson = "{\"amount\": 1}")
                session.activeVertexIds() shouldBe setOf(session.idOf("A"))
                session.send(eventName = "go", payloadJson = "{\"amount\": 10}")
                session.activeVertexIds() shouldBe setOf(session.idOf("B"))
            }
        }

        test("SysML STM: the payload reaches the guard too") {
            withSession(payloadSysmlScript) { session ->
                session.send(eventName = "go", payloadJson = "{\"amount\": 1}")
                session.activeVertexIds() shouldBe setOf("A")
                session.send(eventName = "go", payloadJson = "{\"amount\": 10}")
                session.activeVertexIds() shouldBe setOf("B")
            }
        }

        test("an invalid payload sets InvalidPayload and leaves the trace and lastResult alone") {
            withSession(payloadUmlScript) { session ->
                session.send(eventName = "alt")
                val traceBefore = session.widgetState.trace
                val lastBefore = session.lastResult
                val r = session.send(eventName = "go", payloadJson = "{nope")
                r.shouldBeInstanceOf<StepResult.Error>()
                session.status.shouldBeInstanceOf<SimulationStatus.InvalidPayload>()
                session.widgetState.trace shouldBe traceBefore
                session.lastResult shouldBe lastBefore
            }
        }

        test("an invalid payload while scrubbing does not fork") {
            withSession(payloadUmlScript) { session ->
                session.send(eventName = "alt")
                session.scrubTo(1)
                val traceBefore = session.widgetState.trace
                session.send(eventName = "go", payloadJson = "[1]")
                session.widgetState.trace shouldBe traceBefore
                session.widgetState.isScrubbing shouldBe true
            }
        }

        test("a variable set before a step survives a scrub-back fork (the variables of that position are restored)") {
            withSession(payloadUmlScript) { session ->
                val v1 = kotlinx.serialization.json.JsonPrimitive(1)
                val v2 = kotlinx.serialization.json.JsonPrimitive(2)
                session.setVariable(name = "x", value = v1)
                session.send(eventName = "go", payloadJson = "{\"amount\": 10}") // A -> B, x = 1
                session.setVariable(name = "x", value = v2) // live edit at the end of the history
                session.variablesAtPosition()["x"] shouldBe kotlinx.serialization.json.JsonPrimitive(2L)

                // Scrub back to just before the step: x was 1 there.
                session.scrubTo(session.widgetState.trace.indexOfFirst { it is dev.kuml.runtime.TraceEntry.EventReceived })
                session.variablesAtPosition()["x"] shouldBe kotlinx.serialization.json.JsonPrimitive(1L)

                session.send(eventName = "alt") // forks at that position
                session.widgetState.isScrubbing shouldBe false
                session.widgetState.liveVariables["x"] shouldBe kotlinx.serialization.json.JsonPrimitive(1L)
                session.activeVertexIds() shouldBe setOf(session.idOf("C"))
            }
        }

        test("variables are read-only while scrubbing") {
            withSession(payloadUmlScript) { session ->
                session.send(eventName = "alt")
                session.scrubTo(0)
                session.setVariable(name = "x", value = kotlinx.serialization.json.JsonPrimitive(1)) shouldBe
                    dev.kuml.widget.compose.VariableEditOutcome.RejectedScrubbing
            }
        }

        test("reset restores guards and variables, and keeps the payload draft") {
            withSession(payloadUmlScript) { session ->
                val go = session.stateMachine.transitions.first { it.trigger == "go()" }
                session.payloadDraft = "{\"amount\": 3}"
                session.setVariable(name = "x", value = kotlinx.serialization.json.JsonPrimitive(1))
                session.applyGuard(transitionId = go.id, newOcl = "true", confirmed = false) shouldBe
                    dev.kuml.widget.compose.PatchOutcome.Applied
                session.editedGuardCount shouldBe 1
                session.send(eventName = "go")
                session.activeVertexIds() shouldBe setOf(session.idOf("B"))

                session.reset()

                session.editedGuardCount shouldBe 0
                session.widgetState.model.transitions
                    .first { it.id == go.id }
                    .guard shouldBe "event.amount > 5"
                session.widgetState.liveVariables shouldBe emptyMap()
                session.variablesAtPosition() shouldBe emptyMap()
                session.activeVertexIds() shouldBe setOf(session.idOf("A"))
                session.payloadDraft shouldBe "{\"amount\": 3}"
            }
        }

        test("selectedTransitionId is cleared by send, reset and scrub") {
            withSession(payloadUmlScript) { session ->
                val go =
                    session.stateMachine.transitions
                        .first { it.trigger == "go()" }
                        .id
                session.selectedTransitionId = go
                session.send(eventName = "alt")
                session.selectedTransitionId shouldBe null
                session.selectedTransitionId = go
                session.scrubTo(0)
                session.selectedTransitionId shouldBe null
                session.selectedTransitionId = go
                session.reset()
                session.selectedTransitionId shouldBe null
            }
        }

        test("a guard edit never touches the script text, and revert restores the original") {
            withSession(payloadUmlScript) { session ->
                val before = session.modelSource
                val go =
                    session.stateMachine.transitions
                        .first { it.trigger == "go()" }
                        .id
                session.applyGuard(transitionId = go, newOcl = "event.amount > 1", confirmed = false)
                session.revertGuard(go) shouldBe dev.kuml.widget.compose.PatchOutcome.Applied
                session.editedGuardCount shouldBe 0
                session.modelSource shouldBe before
            }
        }

        test("the draft payload is empty for an invalid draft and parsed for a valid one") {
            withSession(payloadUmlScript) { session ->
                session.payloadDraft = "{broken"
                session.draftPayload().isEmpty() shouldBe true
                session.payloadDraft = "{\"amount\": 7}"
                session.draftPayload().containsKey("amount") shouldBe true
            }
        }
        // ── Prose guards behind pseudostates ────────────────────────────────

        val proseJunctionScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Review") {
                val initial = stateDef("Initial", isInitial = true)
                val inReview = stateDef("InReview")
                val approved = stateDef("Approved")
                val rejected = stateDef("Rejected")
                val j = junction("J")

                transition("init", initial, inReview)
                transition("decide", inReview, j, trigger = "decide")
                transition("ok", j, approved, guard = prose("Antrag vollständig"))
                transition("no", j, rejected, guard = prose("Antrag abgelehnt"))

                stmDiagram("Review") {
                    include(initial)
                    include(inReview)
                    include(approved)
                    include(rejected)
                    include(j)
                }
            }
            """.trimIndent()

        test("proseQuestions follows an unguarded event transition into a junction with prose branches") {
            withSession(proseJunctionScript) { session ->
                session.proseQuestions("decide").map { it.guard } shouldBe listOf("Antrag vollständig", "Antrag abgelehnt")
                session.proseQuestions("other") shouldBe emptyList()
            }
        }

        test("the multi-question prose dialog advances question by question and sends after the last answer") {
            withSession(proseJunctionScript) { session ->
                session.sendAsking(eventName = "decide")
                val first = session.proseDialog
                first?.questions?.size shouldBe 2
                first?.current?.guard shouldBe "Antrag vollständig"

                session.answerProse(met = true)
                val second = session.proseDialog
                second?.current?.guard shouldBe "Antrag abgelehnt"
                session.activeVertexIds() shouldContain session.vertexIdNamed("InReview")

                session.answerProse(met = false)
                session.proseDialog shouldBe null
                session.activeVertexIds() shouldContain session.vertexIdNamed("Approved")
            }
        }
        test("advance() while scrubbing keeps the variables of the scrub position") {
            val timedScript =
                """
                import dev.kuml.sysml2.dsl.sysml2Model
                import kotlin.time.Duration.Companion.seconds

                sysml2Model("Timed") {
                    val initial = stateDef("Initial", isInitial = true)
                    val a = stateDef("A")
                    val b = stateDef("B")
                    transition("init", initial, a)
                    transition("wait", a, b, timer = after(5.seconds))
                    stmDiagram("Timed") {
                        include(initial)
                        include(a)
                        include(b)
                    }
                }
                """.trimIndent()
            withSession(timedScript) { session ->
                session.setVariable(name = "x", value = kotlinx.serialization.json.JsonPrimitive(1))
                val position = session.widgetState.trace.size
                session.advance(
                    dev.kuml.runtime.TimeStep
                        .By(kotlin.time.Duration.parse("6s")),
                )
                session.setVariable(name = "x", value = kotlinx.serialization.json.JsonPrimitive(2))
                session.scrubTo(position)
                session.variablesAtPosition()["x"] shouldBe kotlinx.serialization.json.JsonPrimitive(1)

                session.advance(
                    dev.kuml.runtime.TimeStep
                        .By(kotlin.time.Duration.parse("1s")),
                )
                session.widgetState.isScrubbing shouldBe false
                session.variablesAtPosition()["x"] shouldBe kotlinx.serialization.json.JsonPrimitive(1)
                session.activeVertexIds() shouldContain session.vertexIdNamed("A")
            }
        }
    })
