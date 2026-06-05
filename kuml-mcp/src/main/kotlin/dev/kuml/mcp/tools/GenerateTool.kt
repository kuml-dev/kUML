package dev.kuml.mcp.tools

import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
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

        val scriptFile = Files.createTempFile("kuml-mcp-generate-", ".kuml.kts").toFile()
        scriptFile.writeText(script)

        val outputDir = Files.createTempDirectory("kuml-mcp-generated-").toFile()

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
            scriptFile.delete()
            outputDir.deleteRecursively()
        }
    }
}
