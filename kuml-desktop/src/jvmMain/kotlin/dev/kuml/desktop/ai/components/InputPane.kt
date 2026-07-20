package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings

@Composable
fun InputPane(
    isRunning: Boolean,
    strings: Strings,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            minLines = 2,
            maxLines = 6,
            placeholder = { Text(strings.aiInputPlaceholder) },
        )
        Spacer(Modifier.width(8.dp))
        if (isRunning) {
            IconButton(onClick = onStop) { Text("⏹") }
        } else {
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
            ) {
                Text("▶")
            }
        }
    }
}
