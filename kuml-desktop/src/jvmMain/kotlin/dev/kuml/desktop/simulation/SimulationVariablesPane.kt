package dev.kuml.desktop.simulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.widget.compose.VariableEditOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val ERROR_RED = Color(0xFFCC0000)

/** Short type glyph in front of a variable name. */
internal fun variableGlyph(type: VariableType): String =
    when (type) {
        VariableType.BOOLEAN -> "◐"
        VariableType.NUMBER -> "#"
        VariableType.TEXT -> "“ ”"
        VariableType.READ_ONLY -> "·"
    }

/** The text a number or text value shows in its field. */
private fun editableText(value: JsonElement): String = (value as? JsonPrimitive)?.content.orEmpty()

/**
 * Variables section: the instance variables as typed, validated editors, the variables guards
 * read but nobody set yet, and a row to add one. Read-only while the history is scrubbed.
 */
@Composable
internal fun SimulationVariablesPane(
    session: SimulationSession,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val widgetState = session.widgetState
    val scrubbing = widgetState.isScrubbing
    val variables = session.variablesAtPosition()
    val model = widgetState.model
    val referenced =
        remember(model) {
            model.transitions
                .mapNotNull { it.guard?.takeIf(String::isNotBlank) }
                .flatMap { readVariables(it) }
                .filter { it.root == VarRoot.VARS }
                .map { it.name }
                .distinct()
        }
    val unset = referenced.filter { it !in variables }
    var addName by remember { mutableStateOf("") }

    Column(modifier = modifier.testTag(SimTestTags.VARIABLES)) {
        if (scrubbing) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testTag(SimTestTags.HISTORY_BAND),
            ) {
                Text(text = strings.simViewingHistory, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (variables.isEmpty() && unset.isEmpty()) {
                item { Text(text = strings.simVarNone, fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
            }
            items(variables.entries.toList(), key = { it.key }) { (name, value) ->
                VariableRow(name = name, value = value, readOnly = scrubbing, session = session, strings = strings)
                HorizontalDivider()
            }
            if (unset.isNotEmpty()) {
                item(key = "unset-caption") {
                    Text(
                        text = strings.simVarReferenced,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                }
                items(unset, key = { "unset-$it" }) { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(SimTestTags.variableRow(name)),
                    ) {
                        Text(
                            text = "$name  (${strings.simNotSet})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = !scrubbing, onClick = { addName = name }) { Text(text = strings.simVarSet, fontSize = 11.sp) }
                    }
                }
            }
            item(key = "add") {
                AddVariableRow(session = session, strings = strings, readOnly = scrubbing, name = addName, onNameChange = {
                    addName =
                        it
                })
            }
        }
    }
}

@Composable
private fun VariableRow(
    name: String,
    value: JsonElement,
    readOnly: Boolean,
    session: SimulationSession,
    strings: Strings,
) {
    val type = variableTypeOf(value)
    // A variable an effect created under a name the widget would not accept stays visible but untouchable.
    val editable = !readOnly && type != VariableType.READ_ONLY && isValidVariableName(name)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).testTag(SimTestTags.variableRow(name))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${variableGlyph(type)} $name",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = !readOnly && isValidVariableName(name),
                onClick = { session.removeVariable(name) },
                modifier = Modifier.testTag(SimTestTags.variableUnset(name)),
            ) { Text(text = strings.simVarUnset, fontSize = 11.sp) }
        }
        when (type) {
            VariableType.BOOLEAN ->
                Switch(
                    checked = (value as JsonPrimitive).content == "true",
                    onCheckedChange = { session.setVariable(name = name, value = JsonPrimitive(it)) },
                    enabled = editable,
                    modifier =
                        Modifier
                            .testTag(SimTestTags.variableSwitch(name))
                            .semantics { contentDescription = strings.simVarSwitchA11y.format(name) },
                )
            VariableType.NUMBER, VariableType.TEXT ->
                ValueField(name = name, type = type, value = value, enabled = editable, session = session, strings = strings)
            VariableType.READ_ONLY -> {
                Text(text = formatVariableValue(value = value), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(text = strings.simVarReadOnly, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Text field for a number or text value: Enter or leaving the field commits, Esc discards. */
@Composable
private fun ValueField(
    name: String,
    type: VariableType,
    value: JsonElement,
    enabled: Boolean,
    session: SimulationSession,
    strings: Strings,
) {
    var text by remember(name, value) { mutableStateOf(editableText(value)) }
    var error by remember(name, value) { mutableStateOf<VariableInputError?>(null) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        when (val parsed = parseVariableInput(type = type, raw = text)) {
            is VariableInputResult.Valid -> {
                error = null
                if (parsed.value != value) session.setVariable(name = name, value = parsed.value)
            }
            is VariableInputResult.Invalid -> error = parsed.error
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            error = null
        },
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText =
            error?.let { e ->
                {
                    Text(
                        text = variableErrorText(error = e, strings = strings),
                        modifier = Modifier.testTag(SimTestTags.variableError(name)),
                    )
                }
            },
        textStyle =
            androidx.compose.ui.text
                .TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SimTestTags.variableField(name))
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                commit()
                                true
                            }
                            Key.Escape -> {
                                text = editableText(value)
                                error = null
                                true
                            }
                            else -> false
                        }
                    }
                },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVariableRow(
    session: SimulationSession,
    strings: Strings,
    readOnly: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
) {
    val types =
        listOf(
            VariableType.BOOLEAN to strings.simVarTypeBool,
            VariableType.NUMBER to strings.simVarTypeNumber,
            VariableType.TEXT to strings.simVarTypeText,
        )
    var type by remember { mutableStateOf(VariableType.NUMBER) }
    var raw by remember { mutableStateOf("") }
    var flag by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<VariableInputError?>(null) }

    fun add() {
        val existing = session.variablesAtPosition().keys
        val nameError = validateVariableName(name = name, existing = existing)
        if (nameError != null) {
            error = nameError
            return
        }
        val parsed =
            if (type == VariableType.BOOLEAN) {
                VariableInputResult.Valid(JsonPrimitive(flag))
            } else {
                parseVariableInput(type = type, raw = raw)
            }
        when (parsed) {
            is VariableInputResult.Invalid -> error = parsed.error
            is VariableInputResult.Valid ->
                if (session.setVariable(name = name, value = parsed.value) == VariableEditOutcome.Applied) {
                    error = null
                    onNameChange("")
                    raw = ""
                }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = strings.simVarAdd, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = {
                onNameChange(it)
                error = null
            },
            label = { Text(strings.simVarName) },
            singleLine = true,
            enabled = !readOnly,
            modifier = Modifier.fillMaxWidth().testTag(SimTestTags.ADD_NAME),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            types.forEachIndexed { index, (candidate, label) ->
                SegmentedButton(
                    selected = type == candidate,
                    onClick = {
                        type = candidate
                        error = null
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    enabled = !readOnly,
                ) { Text(text = label, fontSize = 11.sp) }
            }
        }
        if (type == VariableType.BOOLEAN) {
            Switch(
                checked = flag,
                onCheckedChange = { flag = it },
                enabled = !readOnly,
                modifier = Modifier.semantics { contentDescription = strings.simVarValue },
            )
        } else {
            OutlinedTextField(
                value = raw,
                onValueChange = {
                    raw = it
                    error = null
                },
                label = { Text(strings.simVarValue) },
                singleLine = true,
                enabled = !readOnly,
                modifier = Modifier.fillMaxWidth().testTag(SimTestTags.ADD_VALUE),
            )
        }
        error?.let {
            Text(
                text = variableErrorText(error = it, strings = strings),
                fontSize = 10.sp,
                color = ERROR_RED,
                modifier = Modifier.testTag(SimTestTags.ADD_ERROR),
            )
        }
        TextButton(
            enabled = !readOnly,
            onClick = { add() },
            modifier = Modifier.testTag(SimTestTags.ADD_BUTTON),
        ) { Text(strings.simVarSet) }
    }
}
