package dev.kuml.desktop.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel
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
        state = rememberDialogState(width = 640.dp, height = 600.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        McpAvailabilityHint(strings)
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
                            McpTokenButtons(
                                strings = strings,
                                onCopyToken = { copy(manager.currentToken()) },
                                onCopyCommand = {
                                    manager.currentToken()?.let { copy(claudeMcpAddCommand(port = running.port, token = it)) }
                                },
                                onRegenerate = { manager.regenerateToken() },
                            )
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
                    }
                    Row { Button(onClick = onClose) { IconLabel(action = ButtonAction.CLOSE, text = strings.dialogClose) } }
                }
            }
        }
    }
}

/** Persistent hint: when the server is reachable at all. Shown in every state (off, running, error). */
@Composable
internal fun McpAvailabilityHint(strings: Strings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = strings.mcpHint.appMustRun,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.mcpHint.portStable,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The three token actions; wraps onto a second line instead of overflowing narrow dialogs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpTokenButtons(
    strings: Strings,
    onCopyToken: () -> Unit,
    onCopyCommand: () -> Unit,
    onRegenerate: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = onCopyToken) { IconLabel(action = ButtonAction.COPY, text = strings.mcpCopyToken) }
        Button(onClick = onCopyCommand) { IconLabel(action = ButtonAction.COPY, text = strings.mcpCopyCommand) }
        OutlinedButton(onClick = onRegenerate) { IconLabel(action = ButtonAction.REGENERATE, text = strings.mcpRegenerateToken) }
    }
}
