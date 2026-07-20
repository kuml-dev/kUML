package dev.kuml.desktop.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopRenderController
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Undo/Redo callbacks + reactive availability exposed by a mounted [EditorPane]'s
 * underlying `RSyntaxTextArea` (P2, design review). The host (`MainWindow`'s Edit menu)
 * doesn't own the Swing text area, so it receives this small handle via [onEditorReady]
 * instead of reaching into Swing internals itself.
 */
class EditorActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val canUndo: State<Boolean>,
    val canRedo: State<Boolean>,
)

@Composable
fun EditorPane(
    state: AppState,
    controller: DesktopRenderController,
    modifier: Modifier = Modifier,
    onEditorReady: (EditorActions) -> Unit = {},
) {
    val textArea =
        remember {
            RSyntaxTextArea().apply {
                syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_KOTLIN
                antiAliasingEnabled = true
                isCodeFoldingEnabled = true
                tabSize = 4
                text = state.script
                // Undo history starts here, not before — otherwise the very first
                // Undo would clear the initial content (P2, design review).
                discardAllEdits()
            }
        }
    val canUndoState = remember { mutableStateOf(false) }
    val canRedoState = remember { mutableStateOf(false) }

    LaunchedEffect(textArea) {
        onEditorReady(
            EditorActions(
                undo = { if (textArea.canUndo()) textArea.undoLastAction() },
                redo = { if (textArea.canRedo()) textArea.redoLastAction() },
                canUndo = canUndoState,
                canRedo = canRedoState,
            ),
        )
    }

    // Sync editor text when state.script is changed programmatically (e.g. Open action)
    LaunchedEffect(state.script) {
        if (textArea.text != state.script) {
            textArea.text = state.script
            // A different file/script was swapped in — undoing shouldn't cross that
            // boundary back into the previous document's history.
            textArea.discardAllEdits()
            canUndoState.value = false
            canRedoState.value = false
        }
    }

    DisposableEffect(textArea) {
        val listener =
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onChanged()

                override fun removeUpdate(e: DocumentEvent) = onChanged()

                override fun changedUpdate(e: DocumentEvent) = onChanged()

                private fun onChanged() {
                    val newScript = textArea.text
                    if (newScript != state.script) {
                        state.isDirty = true
                    }
                    state.script = newScript
                    controller.scheduleRender(newScript)
                    canUndoState.value = textArea.canUndo()
                    canRedoState.value = textArea.canRedo()
                }
            }
        textArea.document.addDocumentListener(listener)
        onDispose { textArea.document.removeDocumentListener(listener) }
    }

    SwingPanel(
        factory = { RTextScrollPane(textArea) },
        modifier = modifier,
    )
}
