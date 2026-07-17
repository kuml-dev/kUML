package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.ai.ConversationMessage

@Composable
fun ConversationPane(
    messages: List<ConversationMessage>,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var inspectArgs by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            MessageBubble(msg, onInspectToolArgs = { inspectArgs = it })
        }
    }

    inspectArgs?.let { args ->
        ToolArgsInspector(args, onDismiss = { inspectArgs = null })
    }
}
