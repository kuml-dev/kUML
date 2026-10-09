package dev.kuml.desktop.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Writes text to the system clipboard; injectable so the dialog's copy buttons are testable. */
internal fun interface ClipboardWriter {
    fun write(text: String)
}

/** Default [ClipboardWriter] backed by the AWT system clipboard. */
internal object SystemClipboardWriter : ClipboardWriter {
    override fun write(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * The one-liner that registers the running server with Claude Code. The token is base64url
 * (no shell metacharacters). It is only ever copied to the clipboard, never displayed.
 */
internal fun claudeMcpAddCommand(
    port: Int,
    token: String,
): String = "claude mcp add --transport http kuml-desktop http://127.0.0.1:$port/mcp --header \"Authorization: Bearer $token\""

/** Tools ▸ MCP-Server… -- switch, status, and copy buttons for token/command. */
@Composable
internal fun McpServerDialog(
    state: AppState,
    manager: DesktopMcpServerManager,
    strings: Strings,
    onClose: () -> Unit,
    clipboard: ClipboardWriter = SystemClipboardWriter,
) {
    val status by manager.status
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    fun copy(text: String?) {
        if (text == null) return
        clipboard.write(text)
        copied = true
    }

    DialogWindow(
        onCloseRequest = onClose,
        title = strings.mcpDialogTitle,
        state = rememberDialogState(width = 640.dp, height = 480.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = strings.mcpDialogTitle, style = MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = state.mcpServerEnabled, onCheckedChange = { state.mcpServerEnabled = it })
                        Text(strings.mcpEnabled)
                    }
                    Text(
                        text =
                            when (val s = status) {
                                McpServerStatus.Off -> strings.mcpStatusOff
                                is McpServerStatus.Running -> strings.mcpStatusRunning.format(s.port)
                                is McpServerStatus.Error -> strings.mcpStatusError.format(s.message)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    val running = status as? McpServerStatus.Running
                    if (running != null) {
                        Text("${strings.mcpPortLabel}: ${running.port}")
                        Text("${strings.mcpTokenLabel}: ••••••••")
                        if (running.portChanged) {
                            Text(
                                text = strings.mcpPortChanged,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy(manager.currentToken()) }) { Text(strings.mcpCopyToken) }
                            Button(onClick = {
                                manager.currentToken()?.let { copy(claudeMcpAddCommand(port = running.port, token = it)) }
                            }) { Text(strings.mcpCopyCommand) }
                            OutlinedButton(onClick = { manager.regenerateToken() }) { Text(strings.mcpRegenerateToken) }
                        }
                        Text(
                            text = strings.mcpRegenerateWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = strings.mcpCommandWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = strings.mcpSecurityNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (copied) Text(strings.mcpCopied, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f).height(1.dp))
                    Row { Button(onClick = onClose) { Text(strings.dialogClose) } }
                }
            }
        }
    }
}
