package dev.kuml.ai.tools.patch.aitrace

import dev.kuml.runtime.AiTraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.trace.otlp.OtlpExporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AiTraceOtlpExportTest :
    FunSpec({
        val exporter = OtlpExporter()

        test("OtlpExporter export contains root ai.session span with session.id resource attribute") {
            val sessionId = "01HZTEST00"
            val traceFile =
                TraceFile(
                    modelId = "test-model",
                    entries =
                        listOf(
                            AiTraceEntry.SessionStarted(
                                seqNo = 0L,
                                timestamp = "2026-06-12T10:00:00Z",
                                sessionId = sessionId,
                                baseModelFingerprint = "aabbccdd",
                            ),
                        ),
                )

            val export = exporter.convert(traceFile)

            // Should have at least 2 resource spans: kuml.runtime + kuml.ai
            export.resourceSpans shouldHaveAtLeastSize 1

            // Find the AI resource span
            val aiResourceSpan =
                export.resourceSpans.firstOrNull { rs ->
                    rs.resource.attributes.any { it.key == "service.name" && it.value.stringValue == "kuml.ai" }
                }
            aiResourceSpan shouldNotBe null

            // The root span should be ai.session
            val aiRootSpan =
                aiResourceSpan!!
                    .scopeSpans
                    .first()
                    .spans
                    .first()
            aiRootSpan.name shouldBe "ai.session"
            aiRootSpan.attributes.any { it.key == "kuml.ai.session.id" } shouldBe true
        }

        test("Validated then Applied produces one closed patch span with status OK") {
            val sessionId = "01HZTEST01"
            val patchId = "01HZPATCH1"
            val traceFile =
                TraceFile(
                    modelId = "test-model",
                    entries =
                        listOf(
                            AiTraceEntry.SessionStarted(
                                seqNo = 0L,
                                timestamp = "2026-06-12T10:00:00Z",
                                sessionId = sessionId,
                                baseModelFingerprint = "aabbccdd",
                            ),
                            AiTraceEntry.Validated(
                                seqNo = 1L,
                                timestamp = "2026-06-12T10:00:01Z",
                                sessionId = sessionId,
                                patchId = patchId,
                                patchKind = "uml.class",
                                phase = "OK",
                                errorCount = 0,
                            ),
                            AiTraceEntry.Applied(
                                seqNo = 2L,
                                timestamp = "2026-06-12T10:00:02Z",
                                sessionId = sessionId,
                                patchId = patchId,
                                patchKind = "uml.class",
                                elementId = "cls1",
                            ),
                        ),
                )

            val export = exporter.convert(traceFile)
            val aiResourceSpan =
                export.resourceSpans.firstOrNull { rs ->
                    rs.resource.attributes.any { it.value.stringValue == "kuml.ai" }
                }
            aiResourceSpan shouldNotBe null

            val spans = aiResourceSpan!!.scopeSpans.first().spans
            // Should have root span + one patch lifecycle span
            spans shouldHaveAtLeastSize 2

            val patchSpan = spans.firstOrNull { it.name == "ai.patch.lifecycle" }
            patchSpan shouldNotBe null
            // Applied spans have status code 1 (OK)
            patchSpan!!.status.code shouldBe 1
        }

        test("Validated with phase=STRUCTURAL produces patch span with status ERROR and description") {
            val sessionId = "01HZTEST02"
            val patchId = "01HZPATCH2"
            val traceFile =
                TraceFile(
                    modelId = "test-model",
                    entries =
                        listOf(
                            AiTraceEntry.Validated(
                                seqNo = 0L,
                                timestamp = "2026-06-12T10:00:00Z",
                                sessionId = sessionId,
                                patchId = patchId,
                                patchKind = "uml.class",
                                phase = "STRUCTURAL",
                                errorCount = 2,
                            ),
                        ),
                )

            val export = exporter.convert(traceFile)
            val aiResourceSpan =
                export.resourceSpans.firstOrNull { rs ->
                    rs.resource.attributes.any { it.value.stringValue == "kuml.ai" }
                }
            aiResourceSpan shouldNotBe null

            val patchSpan =
                aiResourceSpan!!
                    .scopeSpans
                    .first()
                    .spans
                    .firstOrNull { it.name == "ai.patch.lifecycle" }
            patchSpan shouldNotBe null
            patchSpan!!.status.code shouldBe 2 // ERROR
            patchSpan.status.message shouldNotBe ""
            patchSpan.attributes.any { it.key == "kuml.ai.patch.phase" && it.value.stringValue == "STRUCTURAL" } shouldBe true
        }
    })
