package dev.kuml.desktop.ai.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.ai.ConversationMessage

@Composable
fun MessageBubble(msg: ConversationMessage, onInspectToolArgs: (String) -> Unit) {
    when (msg) {
        is ConversationMessage.User -> UserBubble(msg)
        is ConversationMessage.Assistant -> AssistantBubble(msg)
        is ConversationMessage.ToolCall -> ToolTraceCard(msg, onInspectToolArgs)
        is ConversationMessage.ToolResult -> ToolResultRow(msg)
        is ConversationMessage.ErrorMessage -> ErrorBubble(msg)
    }
}

@Composable
private fun UserBubble(msg: ConversationMessage.User) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                .background(Color(0xFF2D7A4F))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = msg.text,
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AssistantBubble(msg: ConversationMessage.Assistant) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp))
                .background(Color(0xFFF0F0F0))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    text = msg.text + if (msg.isStreaming) " ▌" else "",
                    fontSize = 14.sp,
                    color = Color.Black,
                )
                if (msg.providerId.isNotBlank() || msg.modelId.isNotBlank()) {
                    Text(
                        text = "${msg.providerId}/${msg.modelId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(0.1f))
    }
}

@Composable
private fun ToolResultRow(msg: ConversationMessage.ToolResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = if (msg.isError) "⚠ ${msg.resultJson}" else "↳ ${msg.resultJson.take(80)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (msg.isError) Color(0xFFCC0000) else Color.Gray,
        )
    }
}

@Composable
private fun ErrorBubble(msg: ConversationMessage.ErrorMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFEEEE))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                text = "⚠ ${msg.message}",
                color = Color(0xFFCC0000),
                fontSize = 13.sp,
            )
            if (msg.cause != null) {
                Text(
                    text = msg.cause,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAA0000),
                )
            }
        }
    }
}
