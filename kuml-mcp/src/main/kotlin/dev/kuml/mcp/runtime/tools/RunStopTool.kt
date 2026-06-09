package dev.kuml.mcp.runtime.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.runtime.RuntimeSessionManager
import dev.kuml.mcp.runtime.SessionResult
import dev.kuml.mcp.tools.McpTool
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `kuml.run.stop` — Terminate a session and return the full trace.
 *
 * After this call the session is removed; subsequent calls with the same
 * session ID will return an error.
 */
internal class RunStopTool(
    private val manager: RuntimeSessionManager,
) : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.run.stop",
            description =
                "Terminate a session and return the full execution trace. " +
                    "The session is removed; the session ID becomes invalid.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", "Session ID returned by kuml.run.start.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("sessionId")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val sessionId =
            arguments["sessionId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: sessionId")

        return when (val result = manager.stop(sessionId)) {
            is SessionResult.Stopped -> {
                val json =
                    buildJsonObject {
                        put("ok", true)
                        put("totalSteps", result.totalSteps)
                        put("traceLength", result.traceLength)
                        putJsonArray("trace") {
                            result.trace.forEach { entry ->
                                add(KumlRuntimeJson.encodeToJsonElement(TraceEntry.serializer(), entry))
                            }
                        }
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
