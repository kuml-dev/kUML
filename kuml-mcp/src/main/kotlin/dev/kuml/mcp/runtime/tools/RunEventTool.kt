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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `kuml.run.event` — Send an event to a running STM session.
 *
 * Returns the list of transitions fired, the new active states, and the
 * trace delta since the previous step.
 */
internal class RunEventTool(
    private val manager: RuntimeSessionManager,
) : McpTool {
    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.run.event",
            description =
                "Send an event to a running state-machine session. " +
                    "Returns fired transitions, new active states, and the trace delta.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", "Session ID returned by kuml.run.start.")
                        }
                        putJsonObject("event") {
                            put("type", "string")
                            put("description", "Event name (trigger), e.g. 'powerOn' or 'tick'.")
                        }
                        putJsonObject("payload") {
                            put("type", "object")
                            put("description", "Optional key-value payload accessible in guards as event.<key>.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("sessionId"))
                        add(JsonPrimitive("event"))
                    }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val sessionId =
            arguments["sessionId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: sessionId")
        val eventName =
            arguments["event"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: event")

        // Convert optional payload to Map<String, Any>
        val payloadMap = arguments["payload"]?.jsonObject?.toKotlinMap() ?: emptyMap()

        return when (val result = manager.event(sessionId, eventName, payloadMap)) {
            is SessionResult.Stepped -> {
                val json =
                    buildJsonObject {
                        putJsonArray("fired") { result.fired.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("activeStates") { result.activeStates.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("traceDelta") {
                            result.traceDelta.forEach { entry ->
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

/** Convert a [JsonObject] to a shallow Map<String, Any> for guard evaluation. */
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
