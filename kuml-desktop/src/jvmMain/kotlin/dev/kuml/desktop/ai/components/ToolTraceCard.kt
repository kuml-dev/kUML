package dev.kuml.desktop.ai.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.ai.ConversationMessage
import dev.kuml.desktop.ai.ToolCallState

@Composable
fun ToolTraceCard(
    msg: ConversationMessage.ToolCall,
    onInspect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val icon =
        when (msg.state) {
            ToolCallState.RUNNING -> "⏳"
            ToolCallState.SUCCESS -> "✅"
            ToolCallState.FAILED -> "❌"
        }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
            ) {
                Text(
                    text = "🔧 ${msg.toolName} $icon",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded) {
                Text(
                    text = msg.argsJson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { onInspect(msg.argsJson) }) {
                    Text("Inspect")
                }
            }
        }
    }
}
