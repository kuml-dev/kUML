package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.TaskType
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.tokenflow.TestFixtures
import dev.kuml.runtime.tokenflow.TokenFlowContext
import dev.kuml.runtime.tokenflow.TokenFlowLimits
import dev.kuml.runtime.tokenflow.TokenFlowOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Full-path tests against the two real BPMN OKF vault examples (as required
 * by the task): `03 Bereiche/kUML/Beispiele` → `sample-support-ticket-process
 * /models/eskalationsprozess.md` and `sample-association-charter/models
 * /aufnahme-prozess.md`. The DSL body below is copied verbatim from those
 * notes' ` ```kuml ` blocks (see kuml-examples' knowledge-workspaces
 * resources) rather than parsed from the `.md` file, so this test has no
 * dependency on Markdown parsing or file layout.
 */
class BpmnOkfProcessTest :
    FunSpec({
        fun eskalationsprozess(): BpmnModel =
            bpmnModel(name = "Eskalationsprozess") {
                process(id = "escalation", name = "Eskalationsprozess") {
                    val start = startEvent(name = "Frist ueberschritten")
                    val pruefen = task(name = "Verfuegbaren Senior pruefen", type = TaskType.SERVICE)
                    val verfuegbarGw = gateway(type = GatewayType.EXCLUSIVE, name = "Senior verfuegbar?")
                    val uebernehmen = task(name = "Ticket uebernehmen", type = TaskType.USER)
                    val warten = task(name = "In Warteschlange stellen", type = TaskType.SEND)
                    val benachrichtigen = task(name = "Kunde benachrichtigen", type = TaskType.SEND)
                    val end = endEvent(name = "Eskalation eingeleitet")

                    sequenceFlow(from = start, to = pruefen)
                    sequenceFlow(from = pruefen, to = verfuegbarGw)
                    sequenceFlow(from = verfuegbarGw, to = uebernehmen, condition = "verfuegbar", name = "Ja")
                    sequenceFlow(from = verfuegbarGw, to = warten, condition = "nichtVerfuegbar", name = "Nein", default = true)
                    sequenceFlow(from = uebernehmen, to = benachrichtigen)
                    sequenceFlow(from = warten, to = benachrichtigen)
                    sequenceFlow(from = benachrichtigen, to = end)
                }
                diagram(name = "Eskalationsprozess", processId = "escalation")
            }

        fun aufnahmeverfahren(): BpmnModel =
            bpmnModel(name = "Aufnahmeverfahren") {
                process(id = "admission", name = "Aufnahmeverfahren") {
                    val start = startEvent(name = "Aufnahmeantrag eingegangen")
                    val pruefen = task(name = "Antrag pruefen", type = TaskType.USER)
                    val vollstaendigGw = gateway(type = GatewayType.EXCLUSIVE, name = "Vollstaendig?")
                    val nachfordern = task(name = "Unterlagen nachfordern", type = TaskType.SEND)
                    val beschluss = task(name = "Vorstandsbeschluss", type = TaskType.USER)
                    val genehmigtGw = gateway(type = GatewayType.EXCLUSIVE, name = "Genehmigt?")
                    val aufnehmen = task(name = "Mitglied aufnehmen", type = TaskType.SERVICE)
                    val ablehnen = task(name = "Ablehnung mitteilen", type = TaskType.SEND)
                    val merge = gateway(type = GatewayType.EXCLUSIVE)
                    val end = endEvent(name = "Verfahren abgeschlossen")

                    sequenceFlow(from = start, to = pruefen)
                    sequenceFlow(from = pruefen, to = vollstaendigGw)
                    sequenceFlow(from = vollstaendigGw, to = beschluss, condition = "vollstaendig", name = "Ja")
                    sequenceFlow(from = vollstaendigGw, to = nachfordern, condition = "unvollstaendig", name = "Nein", default = true)
                    sequenceFlow(from = nachfordern, to = pruefen)
                    sequenceFlow(from = beschluss, to = genehmigtGw)
                    sequenceFlow(from = genehmigtGw, to = aufnehmen, condition = "genehmigt", name = "Ja")
                    sequenceFlow(from = genehmigtGw, to = ablehnen, condition = "abgelehnt", name = "Nein", default = true)
                    sequenceFlow(from = aufnehmen, to = merge)
                    sequenceFlow(from = ablehnen, to = merge)
                    sequenceFlow(from = merge, to = end)
                }
                diagram(name = "Aufnahmeverfahren", processId = "admission")
            }

        fun taskNamed(
            model: BpmnModel,
            name: String,
        ) = model.processes
            .single()
            .flowNodes
            .first { it.name == name }
            .id

        test("T-OKF1: escalation happy path — senior available") {
            val model = eskalationsprozess()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            spec.validate().none { it.severity == dev.kuml.runtime.tokenflow.TokenFlowSeverity.ERROR } shouldBe true

            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("verfuegbar" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                result.outcome shouldBe TokenFlowOutcome.Terminated

                val invokedIds = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.nodeId }.toSet()
                invokedIds shouldBe
                    setOf(
                        taskNamed(model, "Verfuegbaren Senior pruefen"),
                        taskNamed(model, "Ticket uebernehmen"),
                        taskNamed(model, "Kunde benachrichtigen"),
                    )
            }
        }

        test("T-OKF2: escalation — explicit default branch (senior not available)") {
            val model = eskalationsprozess()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("verfuegbar" to false, "nichtVerfuegbar" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                val invokedIds = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.nodeId }.toSet()
                invokedIds shouldBe
                    setOf(
                        taskNamed(model, "Verfuegbaren Senior pruefen"),
                        taskNamed(model, "In Warteschlange stellen"),
                        taskNamed(model, "Kunde benachrichtigen"),
                    )
            }
        }

        test("T-OKF3: escalation — no context at all falls back to the default flow (BPMN default semantics)") {
            val model = eskalationsprozess()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val start = sandboxed.engine.start()
                val result = sandboxed.engine.run(initial = start.instance)
                result.outcome shouldBe TokenFlowOutcome.Terminated
                val invokedIds = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.nodeId }
                invokedIds.contains(taskNamed(model, "In Warteschlange stellen")) shouldBe true
                invokedIds.contains(taskNamed(model, "Ticket uebernehmen")) shouldBe false
            }
        }

        test("T-OKF4: admission — approval path") {
            val model = aufnahmeverfahren()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("vollstaendig" to true, "genehmigt" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                result.outcome shouldBe TokenFlowOutcome.Terminated
                val invokedIds = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.nodeId }
                invokedIds.contains(taskNamed(model, "Mitglied aufnehmen")) shouldBe true
                invokedIds.contains(taskNamed(model, "Ablehnung mitteilen")) shouldBe false
                invokedIds.contains(taskNamed(model, "Unterlagen nachfordern")) shouldBe false
            }
        }

        test("T-OKF5: admission — rejection path, XOR-merge forwards without synchronisation") {
            val model = aufnahmeverfahren()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("vollstaendig" to true, "abgelehnt" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                result.outcome shouldBe TokenFlowOutcome.Terminated
                val invokedIds = (start.trace + result.trace).filterIsInstance<TraceEntry.ActivityActionInvoked>().map { it.nodeId }
                invokedIds.contains(taskNamed(model, "Ablehnung mitteilen")) shouldBe true
                invokedIds.contains(taskNamed(model, "Mitglied aufnehmen")) shouldBe false
            }
        }

        test("T-OKF6: admission — genuine cycle (unvollstaendig forever) aborts cleanly via maxSteps, fast") {
            val model = aufnahmeverfahren()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            val startedAtMs = System.currentTimeMillis()
            TestFixtures.engineOf(spec = spec, limits = TokenFlowLimits(maxSteps = 20)).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("unvollstaendig" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                val outcome = result.outcome as? TokenFlowOutcome.LimitExceeded
                outcome?.limit shouldBe "maxSteps"
                // Partial trace is still available for diagnosis — not thrown away.
                (start.trace + result.trace).isNotEmpty() shouldBe true
            }
            (System.currentTimeMillis() - startedAtMs < 5_000L) shouldBe true
        }

        test("T-OKF7: trace-format compatibility — every TokenPlaced.nodeId is a real BPMN flow-node id") {
            val model = eskalationsprozess()
            val process = model.processes.single()
            val spec = BpmnTokenFlowAdapter.toSpec(model = model, diagram = model.diagrams.single() as dev.kuml.bpmn.model.ProcessDiagram)
            TestFixtures.engineOf(spec = spec).use { sandboxed ->
                val ctx = TokenFlowContext(mapOf("verfuegbar" to true))
                val start = sandboxed.engine.start(ctx)
                val result = sandboxed.engine.run(initial = start.instance, context = ctx)
                val full = start.trace + result.trace
                val flowNodeIds = process.flowNodes.map { it.id }.toSet()
                full.filterIsInstance<TraceEntry.TokenPlaced>().all { it.nodeId in flowNodeIds } shouldBe true
            }
        }
    })
