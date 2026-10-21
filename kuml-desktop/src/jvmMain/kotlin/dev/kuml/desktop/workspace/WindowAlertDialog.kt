package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/**
 * Alert-style dialog rendered in its own OS window instead of an in-window popup.
 *
 * Material's `AlertDialog` is drawn inside the main window, and Compose popups are not drawn
 * over heavyweight Swing components. The editor (RSyntaxTextArea) and the SVG preview (Batik
 * canvas) are exactly such components, so an `AlertDialog` opened from the main window is
 * hidden behind them: the user sees nothing happen ("New Workspace…" appeared dead). A
 * [DialogWindow] is a real window and always shows on top, like the About dialog.
 */
@Composable
fun WindowAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    width: Dp = 480.dp,
    height: Dp = 260.dp,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = title,
        state = rememberDialogState(width = width, height = height),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        dismissButton()
                        Row(modifier = Modifier.padding(start = 8.dp)) { confirmButton() }
                    }
                }
            }
        }
    }
}
