package dev.kuml.widget.compose

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Parser and validator for the JSON payload a simulation event carries (`event.<key>` in guards).
 *
 * The payload is user input that reaches the guard evaluator, so it is bounded before anything is
 * allocated or parsed: at most [MAX_BYTES] bytes of UTF-8, at most [MAX_DEPTH] levels of nesting
 * (checked by a linear scan that runs BEFORE the real parser, so pathological nesting never
 * reaches it), top level must be an object and must not use a [RESERVED_KEYS] name.
 */
public object EventPayloads {
    /** Maximum payload size in UTF-8 bytes. */
    public const val MAX_BYTES: Int = 16 * 1024

    /** Maximum nesting depth of objects and arrays. */
    public const val MAX_DEPTH: Int = 8

    /** Top-level keys that would collide with the event's own fields. */
    public val RESERVED_KEYS: Set<String> = setOf("name", "id", "timestamp")

    private val parser = Json

    /**
     * Validates and parses [json]. Blank input is a valid empty payload.
     *
     * Checks run in a fixed order: size, nesting depth, syntax, object shape, reserved keys.
     */
    public fun parse(json: String): PayloadParseResult {
        if (json.isBlank()) return PayloadParseResult.Valid(JsonObject(emptyMap()))
        // Cheap length guard first: a UTF-16 length above MAX_BYTES already exceeds the byte limit
        // for pure-ASCII text; the exact byte count is only computed for shorter inputs.
        if (json.length > MAX_BYTES) return PayloadParseResult.Invalid(error = PayloadError.TOO_LARGE, position = null, detail = null)
        if (json.encodeToByteArray().size > MAX_BYTES) {
            return PayloadParseResult.Invalid(error = PayloadError.TOO_LARGE, position = null, detail = null)
        }
        nestingViolation(json)?.let {
            return PayloadParseResult.Invalid(error = PayloadError.TOO_DEEP, position = it, detail = null)
        }
        val element =
            try {
                parser.parseToJsonElement(json)
            } catch (e: SerializationException) {
                return PayloadParseResult.Invalid(error = PayloadError.SYNTAX, position = offsetOf(e), detail = null)
            } catch (e: IllegalArgumentException) {
                return PayloadParseResult.Invalid(error = PayloadError.SYNTAX, position = offsetOf(e), detail = null)
            }
        val obj =
            element as? JsonObject
                ?: return PayloadParseResult.Invalid(error = PayloadError.NOT_AN_OBJECT, position = null, detail = null)
        obj.keys.firstOrNull { it in RESERVED_KEYS }?.let {
            return PayloadParseResult.Invalid(error = PayloadError.RESERVED_KEY, position = null, detail = it)
        }
        return PayloadParseResult.Valid(obj)
    }

    /** Index of the first character that opens a level deeper than [MAX_DEPTH], or `null`. Strings and escapes are skipped. */
    private fun nestingViolation(text: String): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for ((index, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > MAX_DEPTH) return index
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return null
    }

    private fun offsetOf(e: Exception): Int? =
        Regex("""(?:offset|position) (\d+)""")
            .find(e.message.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}

/** Result of [EventPayloads.parse]. */
public sealed interface PayloadParseResult {
    /** The payload is acceptable; [payload] is the parsed top-level object. */
    public data class Valid(
        public val payload: JsonObject,
    ) : PayloadParseResult

    /** The payload was rejected for [error]; [position] is a character offset when known, [detail] e.g. the reserved key. */
    public data class Invalid(
        public val error: PayloadError,
        public val position: Int?,
        public val detail: String?,
    ) : PayloadParseResult {
        /** English default message, suitable for the widget's own control panel. */
        public val message: String
            get() =
                when (error) {
                    PayloadError.TOO_LARGE -> "Payload too large (max ${EventPayloads.MAX_BYTES} bytes)"
                    PayloadError.TOO_DEEP ->
                        "Payload nested too deeply (max ${EventPayloads.MAX_DEPTH} levels)" + (position?.let { " at position $it" } ?: "")
                    PayloadError.SYNTAX -> "Invalid JSON" + (position?.let { " at position $it" } ?: "")
                    PayloadError.NOT_AN_OBJECT -> "Payload must be a JSON object"
                    PayloadError.RESERVED_KEY -> "Reserved key '${detail.orEmpty()}' is not allowed in a payload"
                }
    }
}

/** Why [EventPayloads.parse] rejected a payload. */
public enum class PayloadError { TOO_LARGE, TOO_DEEP, SYNTAX, NOT_AN_OBJECT, RESERVED_KEY }

/** Thrown by [BehaviourWidgetState.sendEvent] when the payload is invalid; nothing has been changed at that point. */
public class InvalidEventPayloadException(
    public val result: PayloadParseResult.Invalid,
) : IllegalArgumentException(result.message)
