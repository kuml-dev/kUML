package dev.kuml.mcp.tools

import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpScriptEvaluator
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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

        // V0.23.3 — evaluation + extraction run through the sandboxed evaluator.
        // Dispatch mirrors ValidateCommand: UML validates via OclValidator.validate,
        // BPMN / SysML 2 route to their dedicated validators. Any other diagram
        // kind (C4, Blueprint) has no OCL constraint concept and validates
        // trivially.
        val extracted = McpScriptEvaluator.extract(script, "validate.kuml.kts")
        val result: KumlValidationResult =
            when (extracted) {
                is ExtractedDiagram.Uml -> OclValidator.validate(extracted.diagram)
                is ExtractedDiagram.Bpmn -> OclValidator.validateBpmn(extracted.model)
                is ExtractedDiagram.Sysml2 -> OclValidator.validateSysml2(extracted.model)
                else -> KumlValidationResult(valid = true, violations = emptyList())
            }
        val resultJson = json.encodeToString(KumlValidationResult.serializer(), result)
        return listOf(McpContent(type = "text", text = resultJson))
    }
}
