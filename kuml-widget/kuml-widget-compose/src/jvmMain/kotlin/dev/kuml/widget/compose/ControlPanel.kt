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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Control panel composable for the [BehaviourWidget].
 *
 * Shows:
 * - An event name text field + optional payload JSON field.
 * - A "Send" button to dispatch the event.
 * - A "Reset" button to reset the simulation.
 * - A list of currently active state IDs.
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
    }
}
