package dev.kuml.mcp.tools

import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal object ValidateTool : McpTool {
    private val json = Json { prettyPrint = true }

    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.validate",
            description =
                "Validate OCL constraints defined in a kUML DSL script (UML classifiers, " +
                    "BPMN processes, or SysML 2 part definitions). Returns a structured list of " +
                    "constraint violations, each with an optional sourcePosition (line/col within " +
                    "the constraint body).",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put("description", "kUML DSL script content with constraint() declarations.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("script")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")

        KumlScriptGuard.validate(script)
        val scriptFile = Files.createTempFile("kuml-mcp-validate-", ".kuml.kts").toFile()
        scriptFile.writeText(script)

        return try {
            val evalResult = KumlScriptHost.eval(scriptFile)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                throw ScriptEvaluationException("Script evaluation failed:\n$msg")
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: throw ScriptEvaluationException("Script evaluation produced no result")

            // V3.2.23 — mirrors ValidateCommand's dispatch: UML uses the legacy
            // extract() path (kept for backward compatibility with existing MCP
            // clients), BPMN / SysML 2 route through extractAny() to their new
            // OclValidator branches. Any other diagram kind (C4, Blueprint) has
            // no OCL constraint concept and validates trivially.
            val result: KumlValidationResult =
                try {
                    val diagram = DiagramExtractor.extract(success.value.returnValue, scriptFile)
                    OclValidator.validate(diagram)
                } catch (_: ScriptEvaluationException) {
                    when (val extracted = DiagramExtractor.extractAny(success.value.returnValue, scriptFile)) {
                        is ExtractedDiagram.Bpmn -> OclValidator.validateBpmn(extracted.model)
                        is ExtractedDiagram.Sysml2 -> OclValidator.validateSysml2(extracted.model)
                        else -> KumlValidationResult(valid = true, violations = emptyList())
                    }
                }
            val resultJson = json.encodeToString(KumlValidationResult.serializer(), result)
            listOf(McpContent(type = "text", text = resultJson))
        } finally {
            scriptFile.delete()
        }
    }
}
