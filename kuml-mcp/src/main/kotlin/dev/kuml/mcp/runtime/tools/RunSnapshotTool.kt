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
 * `kuml.run.snapshot` — Return the current state of a session without modifying it.
 *
 * Returns active states, variable values, the last 20 trace entries, and the step count.
 */
internal class RunSnapshotTool(
    private val manager: RuntimeSessionManager,
) : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.run.snapshot",
            description =
                "Return a snapshot of the current session state: active states, variables, " +
                    "last 20 trace entries, and step count. Does not modify the session.",
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

        return when (val result = manager.snapshot(sessionId)) {
            is SessionResult.Snapshot -> {
                val json =
                    buildJsonObject {
                        putJsonArray("activeStates") { result.activeStates.forEach { add(JsonPrimitive(it)) } }
                        putJsonObject("variables") {
                            result.variables.forEach { (k, v) -> put(k, v) }
                        }
                        putJsonArray("traceTail") {
                            result.traceTail.forEach { entry ->
                                add(KumlRuntimeJson.encodeToJsonElement(TraceEntry.serializer(), entry))
                            }
                        }
                        put("stepCount", result.stepCount)
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
