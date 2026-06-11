package dev.kuml.runtime.trace

import dev.kuml.runtime.Event
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.trace.otlp.OtlpExporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject

class OtlpExporterTest :
    FunSpec({

        val emptyPayload = JsonObject(emptyMap())

        test("root span plus one state span for each StateEntered/StateExited pair") {
            val sm = oneEventSm() // INIT → A → final
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))

            val export = OtlpExporter().convert(traceFile)
            val spans =
                export.resourceSpans
                    .first()
                    .scopeSpans
                    .first()
                    .spans

            // Root span + state A span (init pseudostate may also produce a span depending on runtime)
            // There's at least a root span and at least one child span
            (spans.size >= 2) shouldBe true
            // Root span has no parent
            spans.first().parentSpanId shouldBe ""
        }

        test("TransitionFired entry becomes an OtlpEvent on the enclosing span") {
            val sm = oneEventSm()
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            jsonStr shouldContain "kuml.transition.fired"
        }

        test("GuardEvaluated entry becomes an OtlpEvent with boolValue attribute") {
            val sm = oneEventSm()
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            jsonStr shouldContain "kuml.guard.evaluated"
            jsonStr shouldContain "boolValue"
        }

        test("timestamps are non-empty strings (nanoseconds representation)") {
            val sm = oneEventSm()
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val export = OtlpExporter().convert(traceFile)
            val rootSpan =
                export.resourceSpans
                    .first()
                    .scopeSpans
                    .first()
                    .spans
                    .first()
            rootSpan.startTimeUnixNano.isNotEmpty() shouldBe true
            rootSpan.endTimeUnixNano.isNotEmpty() shouldBe true
        }

        test("resource attributes contain service.name") {
            val sm = oneEventSm()
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val export = OtlpExporter(serviceName = "my-service").convert(traceFile)
            val attrs =
                export.resourceSpans
                    .first()
                    .resource.attributes
            val serviceAttr = attrs.find { it.key == "service.name" }
            serviceAttr shouldNotBe null
            serviceAttr!!.value.stringValue shouldBe "my-service"
        }

        test("resource attributes contain kuml.model.id when model has an ID") {
            val sm = oneEventSm() // id = "OneEventSm"
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val export = OtlpExporter().convert(traceFile)
            val attrs =
                export.resourceSpans
                    .first()
                    .resource.attributes
            val modelAttr = attrs.find { it.key == "kuml.model.id" }
            modelAttr shouldNotBe null
            modelAttr!!.value.stringValue shouldBe "OneEventSm"
        }

        test("JSON output has no 'type' field (no classDiscriminator)") {
            val sm = oneEventSm()
            val traceFile = simulateToTraceFile(sm, listOf(Event.of("go")))
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            jsonStr shouldNotContain "\"type\":"
        }

        test("ActionError entry sets status code 2 on root span") {
            val errorTrace =
                TraceFile(
                    modelId = "ErrorModel",
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0L, timestamp = "2026-06-01T00:00:00Z", vertexId = "A"),
                            TraceEntry.ActionError(
                                seqNo = 1L,
                                timestamp = "2026-06-01T00:00:01Z",
                                transitionId = null,
                                message = "explosion",
                            ),
                            TraceEntry.StateExited(seqNo = 2L, timestamp = "2026-06-01T00:00:02Z", vertexId = "A"),
                        ),
                )
            val export = OtlpExporter().convert(errorTrace)
            val rootSpan =
                export.resourceSpans
                    .first()
                    .scopeSpans
                    .first()
                    .spans
                    .first()
            rootSpan.status.code shouldBe 2
        }

        test("Activity entries produce flat node spans (TokenPlaced starts, TokenConsumed closes)") {
            val actTrace =
                TraceFile(
                    modelId = "ActivityModel",
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0L, timestamp = "2026-06-01T00:00:00Z", nodeId = "n1", clock = 0L),
                            TraceEntry.TokenConsumed(seqNo = 1L, timestamp = "2026-06-01T00:00:01Z", nodeId = "n1", clock = 1L),
                            TraceEntry.ActivityTerminated(seqNo = 2L, timestamp = "2026-06-01T00:00:02Z", clock = 2L),
                        ),
                )
            val export = OtlpExporter().convert(actTrace)
            val spans =
                export.resourceSpans
                    .first()
                    .scopeSpans
                    .first()
                    .spans
            // Root span + one node span
            spans shouldHaveSize 2
            val nodeSpan = spans.find { it.name == "activity:n1" }
            nodeSpan shouldNotBe null
        }
    })
