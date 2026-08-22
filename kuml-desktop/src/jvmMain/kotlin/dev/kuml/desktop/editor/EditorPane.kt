package dev.kuml.desktop.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import dev.kuml.desktop.AppState
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import org.fife.ui.rtextarea.SearchContext
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Undo/Redo + Find callbacks exposed by a mounted [EditorPane]'s underlying `RSyntaxTextArea`
 * (P2/P8, design review). The host (`MainWindow`'s Edit menu / [dev.kuml.desktop.editor.FindBar])
 * doesn't own the Swing text area, so it receives this small handle via [onEditorReady] instead
 * of reaching into Swing internals itself.
 */
class EditorActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val canUndo: State<Boolean>,
    val canRedo: State<Boolean>,
    /** Sets the search anchor to the current caret position (call when the find bar opens). */
    val beginFind: () -> Unit,
    /**
     * Incremental search; `true` = found. Highlights all matches.
     *
     * [advance] must be `false` for a search re-run purely because the query text or
     * match-case flag changed (typing in the find field), and `true` for an explicit
     * next/previous navigation (Enter/Shift+Enter, the prev/next buttons) -- see
     * [EditorFindController]'s KDoc for why conflating the two breaks both incremental typing
     * and backward navigation.
     */
    val find: (query: String, forward: Boolean, matchCase: Boolean, advance: Boolean) -> Boolean,
    /** Clears highlights, leaves the caret at the last match, returns focus to the editor. */
    val endFind: () -> Unit,
)

/**
 * Pure [SearchContext] builder -- extracted so the flag wiring is unit-testable without a real
 * `RSyntaxTextArea` (kuml-desktop has no Compose UI harness; see the cross-cutting note in the
 * V3.7.4 plan). Regex/whole-word are always off: a find bar accepting arbitrary user text as a
 * regular expression is a catastrophic-backtracking DoS risk on the UI thread (see this welle's
 * security-audit checklist).
 */
internal fun buildSearchContext(
    query: String,
    forward: Boolean,
    matchCase: Boolean,
): SearchContext =
    SearchContext(query).also { ctx ->
        ctx.searchForward = forward
        ctx.setMatchCase(matchCase)
        ctx.setWholeWord(false)
        ctx.setRegularExpression(false)
        ctx.setMarkAll(true)
        // V3.7.5, review fix: the RSTA default is `wrap = false`. Without this, a search
        // starting near one end of the document (e.g. caret left at the end after typing)
        // finds nothing for a query that plainly exists earlier in the text -- and stays
        // stuck reporting "no match" until the find bar is closed and reopened.
        ctx.setSearchWrap(true)
    }

@Composable
fun EditorPane(
    state: AppState,
    modifier: Modifier = Modifier,
    // V3.7.4 (design review P6) — nullable: EditorPane now reports `null` on unmount (see the
    // DisposableEffect below), so a stale handle can no longer point at an abandoned Swing
    // component after a view-mode switch away from an editor-visible mode (the bug the plan
    // calls out: Undo/Redo in the Edit menu staying "enabled" and acting on a disposed
    // RSyntaxTextArea once the DIAGRAM-only view mode unmounts this composable).
    onEditorReady: (EditorActions?) -> Unit = {},
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

    // P8, design review (V3.7.5: extracted into EditorFindController -- see its KDoc for the
    // anchor/advance semantics and why the original in-line closures here got both incremental
    // typing and backward navigation wrong).
    val findController = remember(textArea) { EditorFindController(textArea) }

    LaunchedEffect(textArea) {
        onEditorReady(
            EditorActions(
                undo = { if (textArea.canUndo()) textArea.undoLastAction() },
                redo = { if (textArea.canRedo()) textArea.redoLastAction() },
                canUndo = canUndoState,
                canRedo = canRedoState,
                beginFind = { findController.beginFind() },
                find = { query, forward, matchCase, advance ->
                    findController.find(query = query, forward = forward, matchCase = matchCase, advance = advance)
                },
                endFind = { findController.endFind() },
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
                    // V3.7.4 (design review P6): rendering is no longer triggered from here.
                    // This listener's only job is keeping AppState in sync with the Swing text
                    // area; MainWindow.kt derives ONE render trigger from
                    // RenderInputs(state.script, state.theme, state.showWatermark) via
                    // snapshotFlow, so script changes, theme changes, and watermark toggles all
                    // go through the exact same path instead of only script edits doing so.
                    state.script = newScript
                    canUndoState.value = textArea.canUndo()
                    canRedoState.value = textArea.canRedo()
                }
            }
        textArea.document.addDocumentListener(listener)
        onDispose {
            textArea.document.removeDocumentListener(listener)
            // V3.7.4 (design review P8/undo-redo verification) — report the handle as gone the
            // moment this editor unmounts (e.g. switching to ViewMode.DIAGRAM), so the Edit menu
            // cannot keep calling undo()/redo() against an abandoned RSyntaxTextArea.
            onEditorReady(null)
        }
    }

    SwingPanel(
        factory = { RTextScrollPane(textArea) },
        // P5 — testTag so view-mode tests can assert the editor's presence/absence per mode
        // (analogous to PreviewPane's existing "kuml-preview" tag).
        modifier = modifier.testTag("kuml-editor"),
    )
}
