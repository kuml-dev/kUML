package dev.kuml.mcp.runtime.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.runtime.RuntimeSessionManager
import dev.kuml.mcp.runtime.SessionResult
import dev.kuml.mcp.tools.McpTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `kuml.run.start` — Start a new behaviour-runtime session.
 *
 * Accepts a script path (or inline script), an optional kind hint (`stm`/`act`),
 * and an optional diagram element name.  Returns the session ID and the initial
 * active states.
 */
internal class RunStartTool(
    private val manager: RuntimeSessionManager,
) : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.run.start",
            description =
                "Start a new kUML behaviour-runtime session from a .kuml.kts script. " +
                    "Returns a sessionId used by kuml.run.event, snapshot, patch and stop.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("source") {
                            put("type", "string")
                            put(
                                "description",
                                "Path to a .kuml.kts file, or an inline kUML DSL script string.",
                            )
                        }
                        putJsonObject("kind") {
                            put("type", "string")
                            put(
                                "enum",
                                JsonArray(listOf(JsonPrimitive("stm"), JsonPrimitive("act"))),
                            )
                            put(
                                "description",
                                "Runtime kind: 'stm' for state machine, 'act' for activity. Auto-detected if omitted.",
                            )
                        }
                        putJsonObject("element") {
                            put("type", "string")
                            put("description", "Diagram name to select when the script contains multiple diagrams.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("source")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val source =
            arguments["source"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: source")
        val kind = arguments["kind"]?.jsonPrimitive?.content
        val element = arguments["element"]?.jsonPrimitive?.content

        return when (val result = manager.start(source, kind, element)) {
            is SessionResult.Started -> {
                val json =
                    buildJsonObject {
                        put("sessionId", result.sessionId)
                        put("kind", result.kind)
                        putJsonArray("activeStates") { result.activeStates.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("trace") {}
                    }
                listOf(McpContent(type = "text", text = json.toString()))
            }
            is SessionResult.Error ->
                listOf(McpContent(type = "text", text = buildJsonObject { put("error", result.message) }.toString()))
            else ->
                listOf(McpContent(type = "text", text = buildJsonObject { put("error", "Unexpected result type") }.toString()))
        }
    }
}
