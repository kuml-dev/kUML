package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.Json

@Composable
fun ToolArgsInspector(argsJson: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Tool Args", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = prettyPrint(argsJson),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        }
    }
}

private fun prettyPrint(json: String): String =
    try {
        Json { prettyPrint = true }.parseToJsonElement(json).toString()
    } catch (_: Exception) {
        json
    }
