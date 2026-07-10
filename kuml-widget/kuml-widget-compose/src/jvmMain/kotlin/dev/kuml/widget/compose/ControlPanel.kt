package dev.kuml.widget.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.runtime.defaultGuardScope
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.isProtected

/**
 * Control panel composable for the [BehaviourWidget].
 *
 * Shows:
 * - An event name text field + optional payload JSON field.
 * - A "Send" button to dispatch the event.
 * - A "Reset" button to reset the simulation.
 * - A list of currently active state IDs.
 * - When [EditPolicy.allowsGuardEdit], a "Transitions" list that opens an inline
 *   [OclGuardEditor] per row, with a confirmation dialog for protected transitions.
 *
 * @param state the [BehaviourWidgetState] to interact with.
 * @param modifier optional [Modifier].
 */
@Composable
internal fun ControlPanel(
    state: BehaviourWidgetState,
    modifier: Modifier = Modifier,
) {
    var eventName by remember { mutableStateOf("") }
    var payloadJson by remember { mutableStateOf("{}") }
    val activeIds = state.currentHighlightIds()

    Column(modifier = modifier.padding(8.dp)) {
        Text("Send Event", fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = eventName,
            onValueChange = { eventName = it },
            label = { Text("Event name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = payloadJson,
            onValueChange = { payloadJson = it },
            label = { Text("Payload JSON") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(
                onClick = {
                    if (eventName.isNotBlank()) {
                        state.sendEvent(eventName, payloadJson)
                        eventName = ""
                    }
                },
            ) {
                Text("Send")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(onClick = { state.reset() }) {
                Text("Reset")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Active states:", fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        if (activeIds.isEmpty()) {
            Text("(none)", fontSize = 12.sp)
        } else {
            for (id in activeIds.sorted()) {
                Text("• $id", fontSize = 12.sp)
            }
        }

        if (state.editPolicy.allowsGuardEdit) {
            Spacer(modifier = Modifier.height(12.dp))
            TransitionsSection(state = state)
        }
    }
}

/**
 * Transitions list + inline guard editor host, shown only when the widget's
 * [EditPolicy] allows guard edits.
 */
@Composable
private fun TransitionsSection(state: BehaviourWidgetState) {
    var editing by remember { mutableStateOf<UmlTransition?>(null) }
    var pendingConfirm by remember { mutableStateOf<PendingGuardEdit?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun apply(
        transitionId: String,
        newOcl: String,
        confirmed: Boolean,
    ) {
        when (val outcome = state.changeGuard(transitionId, newOcl, confirmed)) {
            PatchOutcome.Applied -> {
                editing = null
                lastError = null
            }
            // Defensive: the confirmation dialog should already have been shown
            // before we ever get here with confirmed = true.
            PatchOutcome.NeedsConfirmation -> pendingConfirm = PendingGuardEdit(transitionId, newOcl)
            is PatchOutcome.Rejected -> lastError = outcome.message
        }
    }

    val currentEditing = editing
    if (currentEditing != null) {
        Text("Edit guard: ${currentEditing.sourceId} → ${currentEditing.targetId}", fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OclGuardEditor(
            initial = currentEditing.guard.orEmpty(),
            scope = defaultGuardScope(),
            onSave = { newOcl ->
                when (resolveGuardEditAction(state.editPolicy, currentEditing)) {
                    GuardEditAction.Apply -> apply(currentEditing.id, newOcl, confirmed = false)
                    GuardEditAction.Confirm -> pendingConfirm = PendingGuardEdit(currentEditing.id, newOcl)
                    GuardEditAction.Denied -> Unit // unreachable: entry point below is itself gated
                }
            },
            onCancel = {
                editing = null
                lastError = null
            },
        )
        if (lastError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(lastError.orEmpty(), fontSize = 12.sp)
        }
    } else {
        Text("Transitions:", fontSize = 13.sp, modifier = Modifier.testTag(EditorTestTags.TRANSITIONS_SECTION))
        Spacer(modifier = Modifier.height(4.dp))
        if (state.isScrubbing) {
            Text("(return to live to edit guards)", fontSize = 12.sp)
        }
        for (transition in state.model.transitions) {
            val gate = state.editPolicy.guardEditGate(transition)
            val lock = if (transition.isProtected) "🔒 " else ""
            val guardLabel = transition.guard?.let { "[$it]" } ?: "(no guard)"
            val label = "$lock${transition.sourceId} → ${transition.targetId} $guardLabel"
            TextButton(
                onClick = { if (!state.isScrubbing && gate != GuardEditGate.Denied) editing = transition },
                enabled = !state.isScrubbing && gate != GuardEditGate.Denied,
                modifier = Modifier.testTag(EditorTestTags.transitionRow(transition.id)),
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
    }

    val toConfirm = pendingConfirm
    if (toConfirm != null) {
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = {
                Text(
                    "Edit protected transition?",
                    modifier = Modifier.testTag(EditorTestTags.CONFIRM_DIALOG_TITLE),
                )
            },
            text = {
                Text("Transition '${toConfirm.transitionId}' is protected. Confirm to change its guard.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        apply(toConfirm.transitionId, toConfirm.newOcl, confirmed = true)
                        pendingConfirm = null
                    },
                    modifier = Modifier.testTag(EditorTestTags.CONFIRM_APPLY),
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingConfirm = null },
                    modifier = Modifier.testTag(EditorTestTags.CONFIRM_CANCEL),
                ) { Text("Cancel") }
            },
        )
    }
}
