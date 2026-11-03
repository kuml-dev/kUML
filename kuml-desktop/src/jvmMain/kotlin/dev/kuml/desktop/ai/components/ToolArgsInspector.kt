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
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel
import kotlinx.serialization.json.Json

@Composable
fun ToolArgsInspector(
    argsJson: String,
    strings: Strings,
    onDismiss: () -> Unit,
) {
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
                TextButton(onClick = onDismiss) {
                    IconLabel(action = ButtonAction.CLOSE, text = strings.dialogClose)
                }
            }
        }
    }
}

// Single shared pretty-printing Json instance — creating a new Json {} per call
// is flagged by the kotlinx.serialization compiler plugin as needlessly slow.
private val prettyJson = Json { prettyPrint = true }

private fun prettyPrint(json: String): String =
    try {
        prettyJson.parseToJsonElement(json).toString()
    } catch (_: Exception) {
        json
    }
