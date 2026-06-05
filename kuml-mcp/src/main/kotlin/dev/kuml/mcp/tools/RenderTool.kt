package dev.kuml.mcp.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpRenderPipeline
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.util.Base64

internal object RenderTool : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.render",
            description = "Render a kUML DSL script to an SVG or PNG diagram. Returns SVG inline as text or PNG as base64-encoded image.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put(
                                "description",
                                "kUML DSL script content (*.kuml.kts). Must define a diagram using diagram { } or c4Model { }.",
                            )
                        }
                        putJsonObject("format") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("svg"))
                                add(JsonPrimitive("png"))
                            }
                            put("description", "Output format. Default: svg")
                        }
                        putJsonObject("width") {
                            put("type", "integer")
                            put("description", "Width in pixels (PNG only). Default: 1024")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("script")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")
        val format = arguments["format"]?.jsonPrimitive?.content ?: "svg"
        val width = arguments["width"]?.jsonPrimitive?.int ?: 1024

        val scriptFile = writeTemp(script)
        return try {
            when (val result = McpRenderPipeline.render(scriptFile, format, width)) {
                is McpRenderPipeline.RenderResult.Svg ->
                    listOf(
                        McpContent(type = "text", text = result.content),
                    )
                is McpRenderPipeline.RenderResult.Png ->
                    listOf(
                        McpContent(
                            type = "image",
                            data = Base64.getEncoder().encodeToString(result.bytes),
                            mimeType = "image/png",
                        ),
                    )
            }
        } finally {
            scriptFile.delete()
        }
    }

    private fun writeTemp(script: String): File {
        val tmp = Files.createTempFile("kuml-mcp-script-", ".kuml.kts").toFile()
        tmp.writeText(script)
        return tmp
    }
}
