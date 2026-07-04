package dev.kuml.mcp.tools

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpScriptEvaluator
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlUseCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object ListElementsTool : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.list_elements",
            description = "List all model elements (classifiers, relationships) in a kUML script. Returns a human-readable summary.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put("description", "kUML DSL script content to inspect.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("script")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")

        val diagram = evalDiagram(script)
        val sb = StringBuilder()
        sb.appendLine("Diagram: ${diagram.name} (${diagram.elements.size} elements)")
        sb.appendLine()

        val classifiers =
            diagram.elements.filter {
                it is UmlClass || it is UmlInterface || it is UmlEnumeration || it is UmlComponent || it is UmlUseCase
            }
        val relationships = diagram.elements.filter { it is UmlAssociation || it is UmlGeneralization }
        val other = diagram.elements.filter { it !in classifiers && it !in relationships }

        if (classifiers.isNotEmpty()) {
            sb.appendLine("Classifiers (${classifiers.size}):")
            classifiers.forEach { el ->
                val type = el::class.simpleName ?: "Unknown"
                val name =
                    when (el) {
                        is UmlClass -> el.name
                        is UmlInterface -> el.name
                        is UmlEnumeration -> el.name
                        is UmlComponent -> el.name
                        is UmlUseCase -> el.name
                        else -> el.id
                    }
                sb.appendLine("  [$type] $name (id: ${el.id})")
            }
            sb.appendLine()
        }

        if (relationships.isNotEmpty()) {
            sb.appendLine("Relationships (${relationships.size}):")
            relationships.forEach { el ->
                val type = el::class.simpleName ?: "Unknown"
                sb.appendLine("  [$type] id: ${el.id}")
            }
            sb.appendLine()
        }

        if (other.isNotEmpty()) {
            sb.appendLine("Other elements (${other.size}):")
            other.forEach { el ->
                sb.appendLine("  [${el::class.simpleName}] id: ${el.id}")
            }
        }

        return listOf(McpContent(type = "text", text = sb.toString().trimEnd()))
    }

    private fun evalDiagram(script: String): KumlDiagram {
        val extracted = McpScriptEvaluator.extract(script, "list.kuml.kts")
        return (extracted as? ExtractedDiagram.Uml)?.diagram
            ?: throw ScriptEvaluationException(
                "kuml.list_elements currently supports UML diagrams. " +
                    "End the script with a `classDiagram { … }` / `diagram { … }` expression.",
            )
    }
}
