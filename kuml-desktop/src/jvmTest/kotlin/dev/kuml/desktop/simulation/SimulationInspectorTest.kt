package dev.kuml.desktop.simulation

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SimulationInspectorTest :
    FunSpec({

        // init -> C1 ; Parent{C1, C2} ; Parent --quit[vars.ok == true]--> Other ; C1 --next[event.n > 1]--> C2 ;
        // C1 --> Other (completion) ; Other --again--> C1
        fun machine(
            nextGuard: String? = "event.n > 1",
            quitGuard: String? = "vars.ok == true",
        ): UmlStateMachine =
            UmlStateMachine(
                id = "m",
                name = "M",
                vertices =
                    listOf(
                        UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
                        UmlState(
                            id = "Parent",
                            name = "Parent",
                            substates = listOf(UmlState(id = "C1", name = "C1"), UmlState(id = "C2", name = "C2")),
                        ),
                        UmlState(id = "Other", name = "Other"),
                    ),
                transitions =
                    listOf(
                        UmlTransition(id = "t-init", sourceId = "init", targetId = "C1"),
                        UmlTransition(id = "t-quit", sourceId = "Parent", targetId = "Other", trigger = "quit", guard = quitGuard),
                        UmlTransition(id = "t-next", sourceId = "C1", targetId = "C2", trigger = "next", guard = nextGuard),
                        UmlTransition(id = "t-done", sourceId = "C1", targetId = "Other"),
                        UmlTransition(id = "t-again", sourceId = "Other", targetId = "C1", trigger = "again"),
                    ),
            )

        val active = setOf("Parent", "C1")

        // ── readVariables ────────────────────────────────────────────────────

        test("readVariables finds vars and event references in order of first occurrence") {
            readVariables("vars.a > 1 && event.b") shouldContainExactly
                listOf(VariableRef(root = VarRoot.VARS, name = "a"), VariableRef(root = VarRoot.EVENT, name = "b"))
        }

        test("readVariables deduplicates and ignores other roots") {
            readVariables("vars.a == 1 && vars.a != 2 && self.x == 3").shouldContainExactly(VariableRef(root = VarRoot.VARS, name = "a"))
        }

        test("readVariables falls back to a regex scan when the AST parser cannot handle the guard") {
            // OCL collection navigation is not part of the typed AST grammar.
            readVariables("vars.items->size() > 2 and event.limit > 0") shouldContainExactly
                listOf(VariableRef(root = VarRoot.VARS, name = "items"), VariableRef(root = VarRoot.EVENT, name = "limit"))
        }

        test("readVariables strips surrounding brackets like the evaluator does") {
            readVariables("[vars.ready]").shouldContainExactly(VariableRef(root = VarRoot.VARS, name = "ready"))
        }

        test("readVariables caps the result and survives a 5000-operand chain") {
            val guard = (1..5000).joinToString(" && ") { "vars.v$it" }
            val refs = readVariables(guard)
            refs.size shouldBe MAX_READ_VARIABLES
            refs.first() shouldBe VariableRef(root = VarRoot.VARS, name = "v1")
        }

        test("readVariables scans only a bounded prefix of an oversized guard") {
            val guard = "vars.early == 1 " + " ".repeat(MAX_SCANNED_GUARD_CHARS) + "&& vars.late == 2"
            readVariables(guard).shouldContainExactly(VariableRef(root = VarRoot.VARS, name = "early"))
        }

        test("readVariables of a blank guard is empty") {
            readVariables("  ") shouldBe emptyList()
        }

        // ── hierarchy ────────────────────────────────────────────────────────

        test("ancestorChain lists the vertex first and the outermost ancestor last") {
            ancestorChain(model = machine(), vertexId = "C1") shouldContainExactly listOf("C1", "Parent")
            ancestorChain(model = machine(), vertexId = "Other") shouldContainExactly listOf("Other")
        }

        test("withAncestors still adds composite ancestors") {
            withAncestors(model = machine(), activeVertexIds = setOf("C2")) shouldBe setOf("C2", "Parent")
        }

        test("leafIds returns the innermost active states only") {
            leafIds(model = machine(), activeVertexIds = active) shouldBe setOf("C1")
        }

        // ── rows ─────────────────────────────────────────────────────────────

        test("inspectorRows lists the innermost state first, then inherited transitions, completion included") {
            val rows = inspectorRows(model = machine(), initialModel = machine(), activeVertexIds = active)
            rows.map { it.transition.id } shouldContainExactly listOf("t-next", "t-done", "t-quit")
            rows.map { it.inheritedFrom } shouldContainExactly listOf(null, null, "Parent")
            rows.first { it.transition.id == "t-done" }.triggerName shouldBe null
            rows.first { it.transition.id == "t-next" }.triggerName shouldBe "next"
        }

        test("inspectorRows flags edited guards and carries the original") {
            val edited = machine(nextGuard = "true")
            val rows = inspectorRows(model = edited, initialModel = machine(), activeVertexIds = active)
            val next = rows.first { it.transition.id == "t-next" }
            next.edited shouldBe true
            next.originalGuard shouldBe "event.n > 1"
            rows.first { it.transition.id == "t-quit" }.edited shouldBe false
        }

        test("inspectorRows is empty without active states") {
            inspectorRows(model = machine(), initialModel = machine(), activeVertexIds = emptySet()) shouldBe emptyList()
        }

        test("inspectorRows is capped") {
            val many =
                UmlStateMachine(
                    id = "big",
                    name = "Big",
                    vertices = listOf(UmlState(id = "A", name = "A")),
                    transitions = (1..300).map { UmlTransition(id = "t$it", sourceId = "A", targetId = "A", trigger = "e$it") },
                )
            inspectorRows(model = many, initialModel = many, activeVertexIds = setOf("A")).size shouldBe MAX_INSPECTOR_ROWS
        }

        // ── why not ──────────────────────────────────────────────────────────

        val noVars = emptyMap<String, kotlinx.serialization.json.JsonElement>()
        val noPayload = JsonObject(emptyMap())

        test("an event with no transition from the active states is NoTransition with the innermost states") {
            val r =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("again"),
                    evaluations = emptyMap(),
                    variables = noVars,
                    payload = noPayload,
                )
            r.single().shouldBeInstanceOf<WhyNot.NoTransition>().stateIds shouldContainExactly listOf("C1")
        }

        test("all guards false is GuardFalse with the values the guard reads, unset ones as null") {
            val r =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("quit"),
                    evaluations = mapOf("t-quit" to GuardEvaluation.False),
                    variables = noVars,
                    payload = noPayload,
                )
            val item = r.single().shouldBeInstanceOf<WhyNot.GuardFalse>()
            val read =
                item.candidates
                    .single()
                    .reads
                    .single()
            read.ref shouldBe VariableRef(root = VarRoot.VARS, name = "ok")
            read.value shouldBe null

            val withValue =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("quit"),
                    evaluations = mapOf("t-quit" to GuardEvaluation.False),
                    variables = mapOf("ok" to JsonPrimitive(false)),
                    payload = noPayload,
                )
            withValue
                .single()
                .shouldBeInstanceOf<WhyNot.GuardFalse>()
                .candidates
                .single()
                .reads
                .single()
                .value shouldBe JsonPrimitive(false)
        }

        test("a true candidate makes the event enabled, so it is not listed") {
            val r =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("quit", "next"),
                    evaluations = mapOf("t-quit" to GuardEvaluation.True, "t-next" to GuardEvaluation.False),
                    variables = noVars,
                    payload = noPayload,
                )
            r.map { it.eventName } shouldContainExactly listOf("next")
        }

        test("an evaluation error wins over false and a pending evaluation is not listed") {
            val err =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("quit"),
                    evaluations = mapOf("t-quit" to GuardEvaluation.Error("boom")),
                    variables = noVars,
                    payload = noPayload,
                )
            err.single().shouldBeInstanceOf<WhyNot.GuardError>().message shouldBe "boom"
            val pending =
                whyNotExplanations(
                    model = machine(),
                    activeVertexIds = active,
                    eventNames = listOf("quit"),
                    evaluations = emptyMap(),
                    variables = noVars,
                    payload = noPayload,
                )
            pending shouldBe emptyList()
        }

        test("an unguarded candidate is enabled") {
            val r =
                whyNotExplanations(
                    model = machine(quitGuard = null),
                    activeVertexIds = active,
                    eventNames = listOf("quit"),
                    evaluations = emptyMap(),
                    variables = noVars,
                    payload = noPayload,
                )
            r shouldBe emptyList()
        }

        // ── frozen evaluation ────────────────────────────────────────────────

        test("evaluateInspectorGuards maps results, skips blank guards and hands over a frozen copy") {
            val seen = mutableListOf<Triple<String?, ModelInstance<*>, Event>>()
            val evaluator =
                GuardEvaluator { guard, instance, event ->
                    seen += Triple(guard, instance, event)
                    when (guard) {
                        "event.n > 1" -> GuardResult.True
                        "vars.ok == true" -> GuardResult.False
                        else -> GuardResult.Failed("bad")
                    }
                }
            val model = machine()
            val rows = inspectorRows(model = model, initialModel = model, activeVertexIds = active)
            val result =
                runBlocking {
                    evaluateInspectorGuards(
                        evaluator = evaluator,
                        model = model,
                        rows = rows,
                        activeVertexIds = active,
                        variables = mapOf("ok" to JsonPrimitive(3)),
                        payload = JsonObject(mapOf("n" to JsonPrimitive(5))),
                    )
                }
            result["t-next"] shouldBe GuardEvaluation.True
            result["t-quit"] shouldBe GuardEvaluation.False
            result.containsKey("t-done") shouldBe false
            val (_, instance, event) = seen.first { it.first == "event.n > 1" }
            instance.variables["ok"] shouldBe 3L
            instance.currentVertices.map { it.id }.toSet() shouldBe active
            instance.isTerminated shouldBe false
            event.name shouldBe "next"
            event.payload["n"] shouldBe JsonPrimitive(5)
        }

        test("a failed guard becomes an Error evaluation") {
            val model = machine()
            val rows = inspectorRows(model = model, initialModel = model, activeVertexIds = active)
            val result =
                runBlocking {
                    evaluateInspectorGuards(
                        evaluator = { _, _, _ -> GuardResult.Failed("sandbox said no") },
                        model = model,
                        rows = rows,
                        activeVertexIds = active,
                        variables = emptyMap(),
                        payload = JsonObject(emptyMap()),
                    )
                }
            result["t-next"] shouldBe GuardEvaluation.Error("sandbox said no")
        }
    })
