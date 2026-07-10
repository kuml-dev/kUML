package dev.kuml.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.core.ocl.OclCheckResult
import dev.kuml.core.ocl.OclScope
import dev.kuml.core.ocl.OclSyntax
import kotlinx.coroutines.delay

/** Debounce window (ms) before the live type-check re-runs after a keystroke. */
internal const val OCL_TYPECHECK_DEBOUNCE_MS: Long = 250L

/** Save is offered only when the current static check passed. */
internal fun guardSaveEnabled(check: OclCheckResult): Boolean = check is OclCheckResult.Ok

/**
 * Presentational inline OCL guard editor: a syntax-highlighted text field with
 * a debounced live type-check against [scope], an inline error underline plus
 * message line, and Save/Cancel actions.
 *
 * Purely callback-driven — deliberately **not** coupled to
 * [BehaviourWidgetState], [EditPolicy.guardEditGate], or the protected-
 * transition confirmation flow. Those live in Wave 5, which routes `onSave`
 * into a model copy and adds the confirmation dialog for protected
 * transitions. This editor also has no notion of `isScrubbing` — that is a
 * Wave 5 concern too.
 *
 * The type check is seeded synchronously (via `remember`) so the Save button
 * reflects the correct enabled state on the very first frame, before the
 * debounced [LaunchedEffect] below ever runs.
 */
@Composable
internal fun OclGuardEditor(
    initial: String,
    scope: OclScope,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    colors: OclHighlightColors = OclHighlightColors.Default,
) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial)) }
    // Seed synchronously so Save-enabled state is correct on the first frame.
    var check by remember(initial) { mutableStateOf(OclSyntax.typeCheck(initial, scope)) }

    // Debounced live type-check: each keystroke cancels the pending delay.
    LaunchedEffect(value.text) {
        delay(OCL_TYPECHECK_DEBOUNCE_MS)
        check = OclSyntax.typeCheck(value.text, scope)
    }

    val error = check as? OclCheckResult.Error
    val transformation =
        remember(error?.range, colors) {
            OclHighlightTransformation(colors, error?.range)
        }

    Column(modifier = modifier.padding(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Guard (OCL)") },
            singleLine = true,
            isError = error != null,
            visualTransformation = transformation,
            modifier = Modifier.fillMaxWidth().testTag(EditorTestTags.GUARD_INPUT),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = error?.message ?: " ",
            color = colors.errorText,
            fontSize = 12.sp,
            modifier = Modifier.testTag(EditorTestTags.GUARD_ERROR),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { onSave(value.text) },
                enabled = guardSaveEnabled(check),
                modifier = Modifier.testTag(EditorTestTags.GUARD_SAVE),
            ) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onCancel, modifier = Modifier.testTag(EditorTestTags.GUARD_CANCEL)) { Text("Cancel") }
        }
    }
}
