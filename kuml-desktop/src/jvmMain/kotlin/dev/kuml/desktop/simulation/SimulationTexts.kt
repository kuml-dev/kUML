package dev.kuml.desktop.simulation

import dev.kuml.desktop.i18n.Strings
import dev.kuml.widget.compose.EventPayloads
import dev.kuml.widget.compose.PayloadError
import dev.kuml.widget.compose.PayloadParseResult

/** Localized one-line explanation of a rejected event payload (the widget's own English text stays in its module). */
internal fun payloadErrorText(
    result: PayloadParseResult.Invalid,
    strings: Strings,
): String {
    val position = result.position?.let { strings.simPayloadAtPosition.format(it) }.orEmpty()
    return when (result.error) {
        PayloadError.TOO_LARGE -> strings.simPayloadErrTooLarge.format(EventPayloads.MAX_BYTES)
        PayloadError.TOO_DEEP -> strings.simPayloadErrTooDeep.format(EventPayloads.MAX_DEPTH) + position
        PayloadError.SYNTAX -> strings.simPayloadErrSyntax + position
        PayloadError.NOT_AN_OBJECT -> strings.simPayloadErrNotObject
        PayloadError.RESERVED_KEY -> strings.simPayloadErrReserved.format(result.detail.orEmpty())
    }
}

/** Localized explanation of a rejected variable name or value. */
internal fun variableErrorText(
    error: VariableInputError,
    strings: Strings,
): String =
    when (error) {
        VariableInputError.EMPTY -> strings.simVarErrEmpty
        VariableInputError.NOT_A_NUMBER -> strings.simVarErrNotNumber
        VariableInputError.OUT_OF_RANGE -> strings.simVarErrRange
        VariableInputError.NOT_FINITE -> strings.simVarErrNotFinite
        VariableInputError.TOO_LONG -> strings.simVarErrTooLong.format(MAX_TEXT_VALUE_LENGTH)
        VariableInputError.INVALID_NAME -> strings.simVarErrName
        VariableInputError.TOO_MANY -> strings.simVarErrTooMany.format(MAX_VARIABLE_COUNT)
        VariableInputError.NAME_EXISTS -> strings.simVarErrExists
        VariableInputError.READ_ONLY -> strings.simVarErrReadOnly
    }

/** Stable test tags of the simulator side panel (single source of truth for composables and UI tests). */
internal object SimTestTags {
    const val SIDE_PANEL = "sim-side-panel"
    const val INSPECTOR = "sim-inspector"
    const val VARIABLES = "sim-variables"
    const val TEMP_BAND = "sim-temp-band"
    const val HISTORY_BAND = "sim-history-band"
    const val NOT_ENABLED = "sim-not-enabled"
    const val PAYLOAD_FIELD = "sim-payload-field"
    const val PAYLOAD_ERROR = "sim-payload-error"
    const val SEND = "sim-send"
    const val EVENT_FIELD = "sim-event-field"
    const val ADD_NAME = "sim-var-add-name"
    const val ADD_VALUE = "sim-var-add-value"
    const val ADD_BUTTON = "sim-var-add-button"
    const val ADD_ERROR = "sim-var-add-error"
    const val GUARD_EDIT_ERROR = "sim-guard-edit-error"

    fun section(id: String) = "sim-section-$id"

    fun inspectorRow(transitionId: String) = "sim-inspector-row-$transitionId"

    fun editGuard(transitionId: String) = "sim-edit-guard-$transitionId"

    fun revertGuard(transitionId: String) = "sim-revert-guard-$transitionId"

    fun variableRow(name: String) = "sim-var-row-$name"

    fun variableField(name: String) = "sim-var-field-$name"

    fun variableSwitch(name: String) = "sim-var-switch-$name"

    fun variableError(name: String) = "sim-var-error-$name"

    fun variableUnset(name: String) = "sim-var-unset-$name"
}
