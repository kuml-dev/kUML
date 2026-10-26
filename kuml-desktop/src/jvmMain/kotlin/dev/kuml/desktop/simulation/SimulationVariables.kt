package dev.kuml.desktop.simulation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Pure logic behind the simulator's variables panel: typing and parsing of edited values, name
// rules, display formatting and the per-position variable history used while scrubbing.

/** Editable kinds; everything else (null, objects, arrays) is shown read-only. */
internal enum class VariableType { BOOLEAN, NUMBER, TEXT, READ_ONLY }

internal fun variableTypeOf(value: JsonElement): VariableType =
    when {
        value !is JsonPrimitive || value is JsonNull -> VariableType.READ_ONLY
        value.isString -> VariableType.TEXT
        value.content == "true" || value.content == "false" -> VariableType.BOOLEAN
        value.content.toDoubleOrNull() != null -> VariableType.NUMBER
        else -> VariableType.READ_ONLY
    }

internal enum class VariableInputError {
    EMPTY,
    NOT_A_NUMBER,
    OUT_OF_RANGE,
    NOT_FINITE,
    TOO_LONG,
    INVALID_NAME,
    TOO_MANY,
    NAME_EXISTS,
    READ_ONLY,
}

internal sealed interface VariableInputResult {
    data class Valid(
        val value: JsonElement,
    ) : VariableInputResult

    data class Invalid(
        val error: VariableInputError,
    ) : VariableInputResult
}

/** Maximum length of a text variable value. */
internal const val MAX_TEXT_VALUE_LENGTH: Int = 1000

/** Maximum number of variables (mirrors the widget's limit). */
internal const val MAX_VARIABLE_COUNT: Int = 64

private val NUMBER_REGEX = Regex("""^-?\d+(\.\d+)?([eE][+-]?\d+)?$""")
private val NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

/** `true` for names the widget accepts (`[A-Za-z_][A-Za-z0-9_]{0,63}`). */
internal fun isValidVariableName(name: String): Boolean = NAME_REGEX.matches(name)

/**
 * Parses [raw] as a value of [type].
 *
 * Numbers must match a strict decimal grammar first (`toDouble` alone would accept `NaN`,
 * `Infinity`, `0x1p3` and `1.5d`); integral input becomes a Long (out of range is an error), the
 * rest a finite Double.
 */
internal fun parseVariableInput(
    type: VariableType,
    raw: String,
): VariableInputResult =
    when (type) {
        VariableType.BOOLEAN ->
            when (raw.trim().lowercase()) {
                "true" -> VariableInputResult.Valid(JsonPrimitive(true))
                "false" -> VariableInputResult.Valid(JsonPrimitive(false))
                else -> VariableInputResult.Invalid(VariableInputError.EMPTY)
            }
        VariableType.NUMBER -> parseNumber(raw.trim())
        VariableType.TEXT ->
            if (raw.length > MAX_TEXT_VALUE_LENGTH) {
                VariableInputResult.Invalid(VariableInputError.TOO_LONG)
            } else {
                VariableInputResult.Valid(JsonPrimitive(raw))
            }
        VariableType.READ_ONLY -> VariableInputResult.Invalid(VariableInputError.READ_ONLY)
    }

private fun parseNumber(text: String): VariableInputResult {
    if (text.isEmpty()) return VariableInputResult.Invalid(VariableInputError.EMPTY)
    if (!NUMBER_REGEX.matches(text)) return VariableInputResult.Invalid(VariableInputError.NOT_A_NUMBER)
    if ('.' !in text && 'e' !in text && 'E' !in text) {
        val long = text.toLongOrNull() ?: return VariableInputResult.Invalid(VariableInputError.OUT_OF_RANGE)
        return VariableInputResult.Valid(JsonPrimitive(long))
    }
    val double = text.toDouble()
    if (!double.isFinite()) return VariableInputResult.Invalid(VariableInputError.NOT_FINITE)
    return VariableInputResult.Valid(JsonPrimitive(double))
}

/** `null` when [name] may be used for a new variable, otherwise the reason it may not. */
internal fun validateVariableName(
    name: String,
    existing: Set<String>,
    maxCount: Int = MAX_VARIABLE_COUNT,
): VariableInputError? =
    when {
        name.isEmpty() -> VariableInputError.EMPTY
        !isValidVariableName(name) -> VariableInputError.INVALID_NAME
        name in existing -> VariableInputError.NAME_EXISTS
        existing.size >= maxCount -> VariableInputError.TOO_MANY
        else -> null
    }

/** Compact one-line JSON form of [value] for display; `null` (not set) becomes an em dash. Cut at [maxChars]. */
internal fun formatVariableValue(
    value: JsonElement?,
    maxChars: Int = 120,
): String {
    if (value == null) return "—"
    val text = value.toString()
    return if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
}

/**
 * Variable values by trace position, so scrubbing can show (and a fork can restore) the variables
 * as they were. Checkpoints are keyed by TRACE SIZE (one step appends several entries, and the
 * scrub position counts entries, not events). Positions in the middle of a step show the state
 * at the step's beginning, because effects that change variables are not part of the trace.
 */
internal class VariableTimeline(
    initialTraceSize: Int,
    initial: Map<String, JsonElement>,
) {
    private val checkpoints = ArrayList<Pair<Int, Map<String, JsonElement>>>()

    init {
        checkpoints += initialTraceSize to initial
    }

    /** Variables at [position]: the latest checkpoint with `traceSize <= position`, else the first one. */
    fun at(position: Int): Map<String, JsonElement> = checkpoints.lastOrNull { it.first <= position }?.second ?: checkpoints.first().second

    /** Records [variables] as the state at [traceSize]; replaces a checkpoint of the same size. */
    fun record(
        traceSize: Int,
        variables: Map<String, JsonElement>,
    ) {
        while (checkpoints.size > 1 && checkpoints.last().first > traceSize) checkpoints.removeAt(checkpoints.lastIndex)
        if (checkpoints.last().first >= traceSize) {
            checkpoints[checkpoints.lastIndex] = traceSize to variables
        } else {
            checkpoints += traceSize to variables
        }
    }

    /** Drops every checkpoint beyond [position] (the first one always stays). */
    fun truncateAfter(position: Int) {
        while (checkpoints.size > 1 && checkpoints.last().first > position) checkpoints.removeAt(checkpoints.lastIndex)
    }

    fun reset(
        initialTraceSize: Int,
        initial: Map<String, JsonElement>,
    ) {
        checkpoints.clear()
        checkpoints += initialTraceSize to initial
    }
}
