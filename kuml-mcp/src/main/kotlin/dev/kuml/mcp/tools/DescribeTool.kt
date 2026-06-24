package dev.kuml.mcp.tools

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
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

internal object DescribeTool : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.describe",
            description = "Describe a specific model element by its ID: attributes, operations, relationships, and constraints.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put("description", "kUML DSL script content.")
                        }
                        putJsonObject("elementId") {
                            put("type", "string")
                            put("description", "The ID of the element to describe. Use kuml.list_elements to discover IDs.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("script"))
                        add(JsonPrimitive("elementId"))
                    }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")
        val elementId =
            arguments["elementId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: elementId")

        KumlScriptGuard.validate(script)
        val scriptFile = Files.createTempFile("kuml-mcp-describe-", ".kuml.kts").toFile()
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

            val diagram = DiagramExtractor.extract(success.value.returnValue, scriptFile)
            val element =
                diagram.elements.firstOrNull { it.id == elementId }
                    ?: return listOf(
                        McpContent(type = "text", text = "Element '$elementId' not found. Use kuml.list_elements to list available IDs."),
                    )

            val sb = StringBuilder()
            when (element) {
                is UmlClass -> {
                    sb.appendLine("Class: ${element.name} (id: ${element.id})")
                    if (element.isAbstract) sb.appendLine("  abstract: true")
                    if (element.stereotypes.isNotEmpty()) sb.appendLine("  stereotypes: ${element.stereotypes.joinToString()}")
                    if (element.attributes.isNotEmpty()) {
                        sb.appendLine("  Attributes:")
                        element.attributes.forEach { a ->
                            val staticMark = if (a.isStatic) " [static]" else ""
                            val readOnlyMark = if (a.isReadOnly) " [readOnly]" else ""
                            sb.appendLine("    - ${a.name}: ${a.type.name}$staticMark$readOnlyMark")
                        }
                    }
                    if (element.operations.isNotEmpty()) {
                        sb.appendLine("  Operations:")
                        element.operations.forEach { op ->
                            val params = op.parameters.joinToString(", ") { "${it.name}: ${it.type.name}" }
                            sb.appendLine("    - ${op.name}($params): ${op.returnType?.name ?: "void"}")
                        }
                    }
                    if (element.constraints.isNotEmpty()) {
                        sb.appendLine("  Constraints:")
                        element.constraints.forEach { c -> sb.appendLine("    - ${c.name}: ${c.body}") }
                    }
                }
                is UmlInterface -> {
                    sb.appendLine("Interface: ${element.name} (id: ${element.id})")
                    if (element.attributes.isNotEmpty()) {
                        sb.appendLine("  Attributes:")
                        element.attributes.forEach { a -> sb.appendLine("    - ${a.name}: ${a.type.name}") }
                    }
                    if (element.operations.isNotEmpty()) {
                        sb.appendLine("  Operations:")
                        element.operations.forEach { op -> sb.appendLine("    - ${op.name}(${op.parameters.joinToString { it.name }})") }
                    }
                }
                is UmlEnumeration -> {
                    sb.appendLine("Enumeration: ${element.name} (id: ${element.id})")
                    sb.appendLine("  Literals: ${element.literals.joinToString { it.name }}")
                }
                is UmlAssociation -> {
                    sb.appendLine("Association: ${element.id}")
                    val source = element.ends.firstOrNull()?.typeId ?: "?"
                    val target = element.ends.lastOrNull()?.typeId ?: "?"
                    sb.appendLine("  source: $source  →  target: $target")
                    sb.appendLine("  aggregation: ${element.aggregation}")
                }
                is UmlGeneralization -> {
                    sb.appendLine("Generalization: ${element.id}")
                    sb.appendLine("  specific: ${element.specificId}  →  general: ${element.generalId}")
                }
                else -> {
                    sb.appendLine("${element::class.simpleName}: id=${element.id}")
                    sb.appendLine("  (no detailed description available for this element type)")
                }
            }

            listOf(McpContent(type = "text", text = sb.toString().trimEnd()))
        } finally {
            scriptFile.delete()
        }
    }
}
