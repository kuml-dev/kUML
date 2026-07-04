package dev.kuml.mcp.tools

import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpScriptEvaluator
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files

internal object GenerateTool : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.generate",
            description =
                "Generate Kotlin source code from a kUML DSL class diagram script. " +
                    "Returns one text content block per generated file.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put(
                                "description",
                                "kUML DSL script content (*.kuml.kts) defining a class diagram.",
                            )
                        }
                        putJsonObject("plugin") {
                            put("type", "string")
                            put(
                                "description",
                                "Code generator plugin id (e.g. kotlin, java, sql). Default: kotlin",
                            )
                        }
                        putJsonObject("package") {
                            put("type", "string")
                            put("description", "Kotlin package name for generated files (e.g. com.example.domain).")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("script")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")
        val pluginId = arguments["plugin"]?.jsonPrimitive?.content ?: "kotlin"
        val packageName = arguments["package"]?.jsonPrimitive?.content

        val extracted = McpScriptEvaluator.extract(script, "generate.kuml.kts")
        val diagram =
            (extracted as? ExtractedDiagram.Uml)?.diagram
                ?: throw ScriptEvaluationException(
                    "kuml.generate currently supports UML class diagrams. " +
                        "End the script with a `classDiagram { … }` / `diagram { … }` expression.",
                )

        val outputDir = Files.createTempDirectory("kuml-mcp-generated-").toFile()

        return try {
            if (CodeGenRegistry.names().isEmpty()) {
                CodeGenRegistry.loadFromClasspath()
            }
            val generator =
                CodeGenRegistry.get(pluginId)
                    ?: throw IllegalArgumentException(
                        "Unknown codegen plugin: '$pluginId'. " +
                            "Registered plugins: ${CodeGenRegistry.names()}",
                    )
            val options =
                buildMap<String, String> {
                    packageName?.let { put("package", it) }
                }

            val generatedFiles = generator.generate(diagram, outputDir, options)

            generatedFiles.map { file ->
                McpContent(
                    type = "text",
                    text = "// ${file.name}\n${file.readText()}",
                )
            }
        } finally {
            outputDir.deleteRecursively()
        }
    }
}
