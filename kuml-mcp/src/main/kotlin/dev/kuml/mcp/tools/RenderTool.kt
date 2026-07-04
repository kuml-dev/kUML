package dev.kuml.mcp.tools

import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpRenderPipeline
import dev.kuml.mcp.McpScriptEvaluator
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

        // V0.23.3 — evaluation runs through the sandboxed evaluator (guard is
        // enforced inside it as layer 1). Render is UML-only, matching the
        // historical `extract()` contract.
        val extracted = McpScriptEvaluator.extract(script, "render.kuml.kts")
        val diagram =
            (extracted as? ExtractedDiagram.Uml)?.diagram
                ?: throw ScriptEvaluationException(
                    "kuml.render currently supports UML diagrams. " +
                        "End the script with a `classDiagram { … }` / `diagram { … }` expression.",
                )

        return when (val result = McpRenderPipeline.render(diagram, format, width)) {
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
    }
}
