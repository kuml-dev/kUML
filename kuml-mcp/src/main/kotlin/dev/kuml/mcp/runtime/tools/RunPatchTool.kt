package dev.kuml.mcp.runtime.tools

import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.runtime.RuntimeSessionManager
import dev.kuml.mcp.runtime.SessionResult
import dev.kuml.mcp.tools.McpTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `kuml.run.patch` — Patch variables and/or force a state jump in an STM session.
 *
 * Useful for test scaffolding: set variables to specific values before firing an
 * event, or teleport the machine to a named state for targeted testing.
 */
internal class RunPatchTool(
    private val manager: RuntimeSessionManager,
) : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.run.patch",
            description =
                "Patch session variables and/or force the state machine to jump to a named state. " +
                    "Useful for test scaffolding and fault injection.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", "Session ID returned by kuml.run.start.")
                        }
                        putJsonObject("variables") {
                            put("type", "object")
                            put("description", "Key-value pairs to merge into the session variable context.")
                        }
                        putJsonObject("forceState") {
                            put("type", "string")
                            put("description", "State name or ID to teleport the machine to (ignores transitions).")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("sessionId")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val sessionId =
            arguments["sessionId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: sessionId")

        val variablesMap = arguments["variables"]?.jsonObject?.toKotlinMap() ?: emptyMap()
        val forceState = arguments["forceState"]?.jsonPrimitive?.content

        return when (val result = manager.patch(sessionId, variablesMap, forceState)) {
            is SessionResult.Patched -> {
                val json =
                    buildJsonObject {
                        put("ok", result.ok)
                        putJsonArray("activeStates") { result.activeStates.forEach { add(JsonPrimitive(it)) } }
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

/** Convert a [JsonObject] to a shallow Map<String, Any> for variable patching. */
private fun JsonObject.toKotlinMap(): Map<String, Any> =
    buildMap {
        this@toKotlinMap.forEach { (key, element) ->
            when {
                element is JsonPrimitive && element.isString -> put(key, element.content)
                element is JsonPrimitive ->
                    put(
                        key,
                        element.content.toBooleanStrictOrNull()
                            ?: element.content.toLongOrNull()
                            ?: element.content.toDoubleOrNull()
                            ?: element.content,
                    )
                else -> put(key, element.toString())
            }
        }
    }
