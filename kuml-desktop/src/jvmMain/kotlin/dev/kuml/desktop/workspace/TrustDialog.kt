package dev.kuml.desktop.workspace

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.kuml.desktop.i18n.Strings
import java.io.File

/**
 * "Trust this workspace?" modal (V3.6.4, decision 2).
 *
 * Shown whenever a workspace root's canonical path is not yet in
 * [dev.kuml.desktop.AppState.trustedWorkspaces], **before** any document is
 * selected/rendered — i.e. before any ` ```kuml ` block in the workspace is
 * evaluated. [onDecline] must leave the workspace unopened (return to the
 * previous single-file view); only [onTrust] proceeds to open it.
 */
@Composable
fun TrustDialog(
    root: File,
    strings: Strings,
    onTrust: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(strings.workspaceTrustTitle) },
        text = { Text(strings.workspaceTrustMessage.format(root.absolutePath)) },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(strings.workspaceTrustAccept) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(strings.workspaceTrustDecline) }
        },
    )
}
