package dev.kuml.desktop.simulation

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Pure-function tests for [SimulationSupport.kt] — no Compose runtime needed
 * (`kuml-desktop` has no Compose UI test harness, see `MainWindowRenderTriggerTest`).
 */
class SimulationSupportTest :
    FunSpec({

        fun trafficLight(): UmlStateMachine {
            val init = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
            val red = UmlState(id = "Red", name = "Red")
            val green = UmlState(id = "Green", name = "Green")
            val yellow = UmlState(id = "Yellow", name = "Yellow")
            return UmlStateMachine(
                id = "traffic-light",
                name = "Traffic Light",
                vertices = listOf(init, red, green, yellow),
                transitions =
                    listOf(
                        UmlTransition(id = "t-init-red", sourceId = "init", targetId = "Red"),
                        UmlTransition(id = "t-red-green", sourceId = "Red", targetId = "Green", trigger = "next()"),
                        UmlTransition(id = "t-green-yellow", sourceId = "Green", targetId = "Yellow", trigger = "next()"),
                        UmlTransition(id = "t-yellow-red", sourceId = "Yellow", targetId = "Red", trigger = "next()"),
                    ),
            )
        }

        // ── isSimulatable ────────────────────────────────────────────────────

        test("isSimulatable is true for UML STATE diagrams") {
            val extracted = ExtractedDiagram.Uml(KumlDiagram(name = "D", type = DiagramType.STATE, elements = listOf(trafficLight())))
            isSimulatable(extracted) shouldBe true
        }

        test("isSimulatable is false for UML CLASS diagrams") {
            val extracted = ExtractedDiagram.Uml(KumlDiagram(name = "D", type = DiagramType.CLASS, elements = emptyList()))
            isSimulatable(extracted) shouldBe false
        }

        test("isSimulatable is true for SysML-2 StmDiagram") {
            val model = Sysml2Model(name = "M")
            val extracted = ExtractedDiagram.Sysml2(model = model, diagram = StmDiagram(name = "Stm"))
            isSimulatable(extracted) shouldBe true
        }

        test("isSimulatable is false for SysML-2 BdDiagram") {
            val model = Sysml2Model(name = "M")
            val extracted = ExtractedDiagram.Sysml2(model = model, diagram = BdDiagram(name = "Bd"))
            isSimulatable(extracted) shouldBe false
        }

        // ── allEventNames ────────────────────────────────────────────────────

        test("allEventNames returns names in first-occurrence order, deduplicated") {
            allEventNames(trafficLight()) shouldBe listOf("next")
        }

        test("allEventNames extracts the bare name before the parenthesis") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    transitions = listOf(UmlTransition(id = "t1", sourceId = "a", targetId = "b", trigger = "confirm(payload)")),
                )
            allEventNames(sm) shouldBe listOf("confirm")
        }

        test("allEventNames drops null and blank triggers") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    transitions =
                        listOf(
                            UmlTransition(id = "t1", sourceId = "a", targetId = "b", trigger = null),
                            UmlTransition(id = "t2", sourceId = "b", targetId = "c", trigger = "   "),
                            UmlTransition(id = "t3", sourceId = "c", targetId = "d", trigger = "go()"),
                        ),
                )
            allEventNames(sm) shouldBe listOf("go")
        }

        test("allEventNames preserves order across two distinct events, first occurrence wins") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    transitions =
                        listOf(
                            UmlTransition(id = "t1", sourceId = "a", targetId = "b", trigger = "beta()"),
                            UmlTransition(id = "t2", sourceId = "b", targetId = "c", trigger = "alpha()"),
                            UmlTransition(id = "t3", sourceId = "c", targetId = "a", trigger = "beta()"),
                        ),
                )
            allEventNames(sm) shouldBe listOf("beta", "alpha")
        }

        // ── withAncestors ────────────────────────────────────────────────────

        test("withAncestors returns the active set unchanged when there is no nesting") {
            withAncestors(model = trafficLight(), activeVertexIds = setOf("Red")) shouldBe setOf("Red")
        }

        test("withAncestors adds every composite ancestor, three levels deep") {
            val inner = UmlState(id = "inner", name = "inner")
            val middle = UmlState(id = "middle", name = "middle", substates = listOf(inner))
            val outer = UmlState(id = "outer", name = "outer", substates = listOf(middle))
            val sm = UmlStateMachine(id = "sm", name = "SM", vertices = listOf(outer))

            withAncestors(model = sm, activeVertexIds = setOf("inner")) shouldBe setOf("inner", "middle", "outer")
        }

        // ── enabledEventNames ────────────────────────────────────────────────

        test("enabledEventNames returns the transition offered by the active state") {
            enabledEventNames(model = trafficLight(), activeVertexIds = setOf("Red")) shouldBe setOf("next")
        }

        test("enabledEventNames is empty for an empty active set") {
            enabledEventNames(model = trafficLight(), activeVertexIds = emptySet()) shouldBe emptySet()
        }

        test("enabledEventNames ignores transitions without a trigger") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    transitions = listOf(UmlTransition(id = "t1", sourceId = "a", targetId = "b", trigger = null)),
                )
            enabledEventNames(model = sm, activeVertexIds = setOf("a")) shouldBe emptySet()
        }

        test("enabledEventNames merges triggers from two active sources into one set") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    transitions =
                        listOf(
                            UmlTransition(id = "t1", sourceId = "a", targetId = "c", trigger = "go()"),
                            UmlTransition(id = "t2", sourceId = "b", targetId = "c", trigger = "go()"),
                        ),
                )
            enabledEventNames(model = sm, activeVertexIds = setOf("a", "b")) shouldBe setOf("go")
        }

        test("enabledEventNames includes a transition declared on a composite ancestor of an active substate") {
            val inner = UmlState(id = "inner", name = "inner")
            val outer = UmlState(id = "outer", name = "outer", substates = listOf(inner))
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    vertices = listOf(outer),
                    transitions = listOf(UmlTransition(id = "t1", sourceId = "outer", targetId = "outer", trigger = "escape()")),
                )
            enabledEventNames(model = sm, activeVertexIds = setOf("inner")) shouldBe setOf("escape")
        }

        // ── autoAdvanceDecision ──────────────────────────────────────────────

        test("autoAdvanceDecision continues with the single enabled event") {
            autoAdvanceDecision(enabledEvents = setOf("next"), lastResult = null, terminated = false) shouldBe
                AutoAdvanceDecision.Continue("next")
        }

        test("autoAdvanceDecision stops with NO_EVENT when nothing is enabled") {
            autoAdvanceDecision(enabledEvents = emptySet(), lastResult = null, terminated = false) shouldBe
                AutoAdvanceDecision.Stop(AutoAdvanceStopReason.NO_EVENT)
        }

        test("autoAdvanceDecision stops with AMBIGUOUS when two events are enabled") {
            autoAdvanceDecision(enabledEvents = setOf("a", "b"), lastResult = null, terminated = false) shouldBe
                AutoAdvanceDecision.Stop(AutoAdvanceStopReason.AMBIGUOUS)
        }

        test("autoAdvanceDecision stops with STAYED after a Stayed result") {
            autoAdvanceDecision(
                enabledEvents = setOf("next"),
                lastResult = StepResult.Stayed("no transition"),
                terminated = false,
            ) shouldBe AutoAdvanceDecision.Stop(AutoAdvanceStopReason.STAYED)
        }

        test("autoAdvanceDecision stops with GUARD_FAILED after a GuardFailed result") {
            autoAdvanceDecision(
                enabledEvents = setOf("next"),
                lastResult = StepResult.GuardFailed(transitionId = "t1", message = "nope"),
                terminated = false,
            ) shouldBe AutoAdvanceDecision.Stop(AutoAdvanceStopReason.GUARD_FAILED)
        }

        test("autoAdvanceDecision stops with ERROR after an Error result") {
            autoAdvanceDecision(
                enabledEvents = setOf("next"),
                lastResult = StepResult.Error(RuntimeException("boom")),
                terminated = false,
            ) shouldBe AutoAdvanceDecision.Stop(AutoAdvanceStopReason.ERROR)
        }

        test("autoAdvanceDecision stops with TERMINATED when terminated is true, even with one enabled event") {
            autoAdvanceDecision(enabledEvents = setOf("next"), lastResult = null, terminated = true) shouldBe
                AutoAdvanceDecision.Stop(AutoAdvanceStopReason.TERMINATED)
        }

        test("autoAdvanceDecision stops with STALE when the script has changed, even with one enabled event") {
            autoAdvanceDecision(enabledEvents = setOf("next"), lastResult = null, terminated = false, stale = true) shouldBe
                AutoAdvanceDecision.Stop(AutoAdvanceStopReason.STALE)
        }

        test("autoAdvanceDecision prefers STALE over TERMINATED when both are true") {
            autoAdvanceDecision(enabledEvents = setOf("next"), lastResult = null, terminated = true, stale = true) shouldBe
                AutoAdvanceDecision.Stop(AutoAdvanceStopReason.STALE)
        }

        test("autoAdvanceDecision defaults stale to false when the caller omits it") {
            autoAdvanceDecision(enabledEvents = setOf("next"), lastResult = null, terminated = false) shouldBe
                AutoAdvanceDecision.Continue("next")
        }

        // ── isStale ──────────────────────────────────────────────────────────

        test("isStale is false for an identical script") {
            isStale(sessionSource = "abc", currentScript = "abc") shouldBe false
        }

        test("isStale is true after a single character change") {
            isStale(sessionSource = "abc", currentScript = "abd") shouldBe true
        }

        test("isStale is true for an empty script against a non-empty session source") {
            isStale(sessionSource = "abc", currentScript = "") shouldBe true
        }

        // ── highlightDiff ────────────────────────────────────────────────────

        test("highlightDiff from an empty previous set shows everything") {
            highlightDiff(previous = emptySet(), current = setOf("A", "B")) shouldBe
                HighlightDiff(show = setOf("A", "B"), hide = emptySet())
        }

        test("highlightDiff between identical sets has empty show and hide") {
            highlightDiff(previous = setOf("A"), current = setOf("A")) shouldBe HighlightDiff(show = emptySet(), hide = emptySet())
        }

        test("highlightDiff between disjoint sets shows the new one and hides the old one") {
            highlightDiff(previous = setOf("A"), current = setOf("B")) shouldBe HighlightDiff(show = setOf("B"), hide = setOf("A"))
        }

        test("highlightDiff with partial overlap only reports the actual delta") {
            highlightDiff(previous = setOf("A", "B"), current = setOf("B", "C")) shouldBe
                HighlightDiff(show = setOf("C"), hide = setOf("A"))
        }

        // ── newGuardWarnings ─────────────────────────────────────────────────

        test("newGuardWarnings is empty when the trace has no warnings") {
            val trace = listOf(TraceEntry.Stayed(seqNo = 0, timestamp = "t", reason = "x"))
            newGuardWarnings(trace = trace, beforeSize = 0) shouldBe emptyList()
        }

        test("newGuardWarnings returns exactly the entries at or after beforeSize") {
            val trace =
                listOf(
                    TraceEntry.EventReceived(
                        seqNo = 0,
                        timestamp = "t",
                        eventName = "go",
                        payload = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                    TraceEntry.GuardWarning(seqNo = 1, timestamp = "t", transitionId = "t1", guard = "g", message = "failed"),
                )
            newGuardWarnings(trace = trace, beforeSize = 1) shouldBe
                listOf(TraceEntry.GuardWarning(seqNo = 1, timestamp = "t", transitionId = "t1", guard = "g", message = "failed"))
        }

        test("newGuardWarnings ignores a warning that lies before beforeSize") {
            val trace = listOf(TraceEntry.GuardWarning(seqNo = 0, timestamp = "t", transitionId = "t1", guard = "g", message = "failed"))
            newGuardWarnings(trace = trace, beforeSize = 1) shouldBe emptyList()
        }

        test("newGuardWarnings collects multiple warnings in the delta") {
            val trace =
                listOf(
                    TraceEntry.GuardWarning(seqNo = 0, timestamp = "t", transitionId = "t1", guard = "g1", message = "m1"),
                    TraceEntry.GuardWarning(seqNo = 1, timestamp = "t", transitionId = "t2", guard = "g2", message = "m2"),
                )
            newGuardWarnings(trace = trace, beforeSize = 0).size shouldBe 2
        }
    })
