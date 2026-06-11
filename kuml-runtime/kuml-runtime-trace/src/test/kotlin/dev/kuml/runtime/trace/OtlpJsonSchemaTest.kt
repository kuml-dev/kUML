package dev.kuml.runtime.trace

import dev.kuml.runtime.trace.otlp.OtlpExporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OtlpJsonSchemaTest :
    FunSpec({

        val sm = oneEventSm()
        val traceFile =
            simulateToTraceFile(
                sm,
                listOf(
                    dev.kuml.runtime.Event
                        .of("go"),
                ),
            )

        test("exported JSON is parseable by a standard Json instance") {
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            // Should parse without exception
            val parsed = Json.parseToJsonElement(jsonStr)
            parsed.jsonObject // assert it's a JSON object
        }

        test("top-level JSON has 'resourceSpans' key") {
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            jsonStr shouldContain "\"resourceSpans\""
        }

        test("JSON does not contain a 'type' discriminator field") {
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            jsonStr shouldNotContain "\"type\":"
        }

        test("JSON does not have 'classDiscriminator' artifacts") {
            val jsonStr = OtlpExporter().exportToJson(traceFile)
            // classDiscriminator = "type" from KumlRuntimeJson would inject "type" fields
            // Verify the OTLP output is clean
            jsonStr shouldNotContain "\"type\": \"StateEntered\""
            jsonStr shouldNotContain "\"type\": \"EventReceived\""
        }
    })
