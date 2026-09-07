package dev.kuml.desktop.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Pure decision (bugfix, review finding) — whether an incoming external text replacement
 * belongs to a genuinely different document (full reset warranted) or the SAME document
 * merely being spliced from outside (caret/undo history should survive). Extracted out of
 * `SyntaxTextEditor`'s `LaunchedEffect(text)` so it is unit-testable without a Compose
 * harness, same reasoning as [SyntaxTextEditorTest]'s existing smoke tests. `documentKey ==
 * null` always answers `true` — see [SyntaxTextEditor]'s `documentKey` KDoc for why that is
 * the correct default for the plain `.kuml.kts` script editor.
 */
internal fun isExternalTextChangeANewDocument(
    lastDocumentKey: Any?,
    documentKey: Any?,
): Boolean = documentKey == null || lastDocumentKey != documentKey

/**
 * Pure application of an external text replacement onto a real `RSyntaxTextArea` (bugfix,
 * review finding) — extracted out of `SyntaxTextEditor`'s `LaunchedEffect(text)` for the same
 * headless-testability reason as [isExternalTextChangeANewDocument]. A new document ([isNewDocument]
 * `true`) is a full replace with the undo history cleared; the same document being spliced
 * from outside preserves the caret position (clamped into the new text's bounds) and leaves
 * the undo history intact — the replace itself becomes one more undoable step.
 */
internal fun applyExternalTextChange(
    textArea: RSyntaxTextArea,
    newText: String,
    isNewDocument: Boolean,
) {
    if (isNewDocument) {
        textArea.text = newText
        textArea.discardAllEdits()
    } else {
        val caret = textArea.caretPosition
        textArea.text = newText
        textArea.caretPosition = caret.coerceIn(0, textArea.document.length)
    }
}

/**
 * Runs [applyExternalTextChange] with [suppressListenerNotifications] held `true` for the
 * call's whole duration (CRITICAL bugfix, review finding). `RSyntaxTextArea.setText()` is
 * implemented as `AbstractDocument.replace()`, i.e. an internal `remove(0, len)` followed by
 * `insertString(...)` — that fires FOUR `DocumentEvent`s on `textArea.document`'s listener(s)
 * while the programmatic replace in [applyExternalTextChange] is in flight, and the first two
 * observe the document transiently EMPTY (verified against a real, headless `RSyntaxTextArea`
 * in [SyntaxTextEditorTest]). A listener that compares the event's text against some snapshot
 * of the target value cannot reliably tell those two empty events apart from a real user edit
 * once that snapshot has already caught up to the new value (exactly what happened with
 * `SyntaxTextEditor`'s `rememberUpdatedState`-backed `currentText` before this fix): the empty
 * events get propagated outward as `onTextChange("")`, and the two correct, fully-replaced
 * events are then suppressed as "unchanged" against that same already-updated snapshot — net
 * effect, the outside world is told the document is now empty, and (via `EditorPane`/
 * `DocumentEditorPane`'s own state) the *actual* buffer gets overwritten with `""` on the next
 * recomposition. Extracted as its own function (rather than inlined in `SyntaxTextEditor`'s
 * `LaunchedEffect(text)`) purely so [SyntaxTextEditorTest] can exercise this exact guard
 * against a real `RSyntaxTextArea` + `DocumentListener` without needing a Compose test harness
 * (`kuml-desktop` has none) — production behaviour is unchanged by the extraction.
 */
internal fun applyExternalTextChangeSilently(
    textArea: RSyntaxTextArea,
    newText: String,
    isNewDocument: Boolean,
    suppressListenerNotifications: AtomicBoolean,
) {
    suppressListenerNotifications.set(true)
    try {
        applyExternalTextChange(textArea = textArea, newText = newText, isNewDocument = isNewDocument)
    } finally {
        suppressListenerNotifications.set(false)
    }
}

/**
 * A single `RSyntaxTextArea`-backed Compose editor, generic over its text source (V-next,
 * editable-workspace welle) — extracted out of [EditorPane] so the OKF document editor
 * (`DocumentEditorPane`) can reuse the exact same RSTA plumbing (undo history, find
 * controller, the dirty-tracking `DocumentListener`) against an arbitrary `(text,
 * onTextChange)` pair instead of being hard-wired to [dev.kuml.desktop.AppState.script].
 *
 * [EditorPane] is now a thin adapter over this composable — see its own KDoc for the one
 * behavioural subtlety (dirty-flag ordering) callers must preserve.
 */
@Composable
internal fun SyntaxTextEditor(
    text: String,
    onTextChange: (String) -> Unit,
    syntaxStyle: String,
    modifier: Modifier = Modifier,
    testTag: String = "kuml-editor",
    onEditorReady: (EditorActions?) -> Unit = {},
    /**
     * Fired when Escape is pressed while this editor has focus (opt-in; the plain
     * `.kuml.kts` script editor leaves this as a no-op — `Main.kt`'s Window-level Escape
     * handling for an active simulation is unrelated and unaffected, since this binds only
     * on THIS `RSyntaxTextArea` instance's own input map). Used by `DocumentEditorPane` to
     * switch back to the read view on a clean buffer.
     */
    onEscape: () -> Unit = {},
    /**
     * Identifies which document/file [text] belongs to (bugfix, review finding). When this
     * changes between recompositions, an incoming [text] that differs from the editor's
     * current content is treated as a genuinely NEW document — full replace, undo history
     * cleared, caret reset to the start. When it stays the SAME but [text] is still replaced
     * wholesale from outside (e.g. `DocumentHeaderBar`'s title/type fields splicing a new
     * value into the frontmatter of the SAME buffer via `FrontmatterWriter.setField`), the
     * caret position is preserved and the undo history is left intact instead.
     *
     * Defaults to `null`, which means "always treat an external replace as a new document" —
     * the ORIGINAL behaviour, preserved as the default for the plain `.kuml.kts` script
     * editor ([EditorPane]), which has exactly one document per editor instance and never
     * passes this parameter: there, every external `text` change already IS either a load of
     * a genuinely different file, or Undo/Redo itself (which manages its own history), so
     * there is nothing to preserve. [DocumentEditorPane] passes the selected document's
     * `relativePath` so a same-document splice (title/type) no longer wipes 200 lines of
     * in-progress editing just because the user also touched the title field once.
     */
    documentKey: Any? = null,
) {
    val textArea =
        remember {
            RSyntaxTextArea().apply {
                syntaxEditingStyle = syntaxStyle
                antiAliasingEnabled = true
                isCodeFoldingEnabled = true
                tabSize = 4
                this.text = text
                // Undo history starts here, not before — otherwise the very first
                // Undo would clear the initial content (P2, design review).
                discardAllEdits()
            }
        }
    val canUndoState = remember { mutableStateOf(false) }
    val canRedoState = remember { mutableStateOf(false) }

    val findController = remember(textArea) { EditorFindController(textArea) }

    // The DocumentListener below lives inside a DisposableEffect keyed on `textArea` (stable —
    // remembered once), so its body runs exactly once for this composable's whole lifetime. A
    // closure created there that referred to `text`/`onTextChange` directly would permanently
    // capture the FIRST composition's values (a real staleness bug, unlike EditorPane's
    // original `state.script`, which is a live property read on a stable object reference, not
    // a captured value parameter). `rememberUpdatedState` is the idiomatic fix: reading
    // `.value` inside the closure always sees the latest composition's argument.
    val currentText by rememberUpdatedState(text)
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnEscape by rememberUpdatedState(onEscape)

    DisposableEffect(textArea) {
        val actionKey = "kuml-syntax-text-editor-escape"
        val keyStroke = KeyStroke.getKeyStroke("ESCAPE")
        textArea.inputMap.put(keyStroke, actionKey)
        textArea.actionMap.put(
            actionKey,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = currentOnEscape()
            },
        )
        onDispose {
            textArea.inputMap.remove(keyStroke)
            textArea.actionMap.remove(actionKey)
        }
    }

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

    // Tracks the [documentKey] as of the last time this effect actually replaced the text —
    // `remember`ed (not a plain local val) so it survives across this LaunchedEffect's many
    // restarts (it restarts on every `text` change, i.e. on every keystroke that round-trips
    // through `onTextChange`/back into `text`).
    val lastDocumentKey = remember { mutableStateOf(documentKey) }

    // Guards against the CRITICAL data-loss bug found in review — see
    // [applyExternalTextChangeSilently]'s KDoc for the full mechanism. A plain
    // (non-Compose-state) `AtomicBoolean` is enough since both reads/writes happen on the AWT
    // event-dispatch thread within the same synchronous call chain -- it just needs to survive
    // this composable's recompositions, hence `remember`.
    val applyingExternalChange = remember { AtomicBoolean(false) }

    // Sync editor text when [text] is changed programmatically (e.g. a different document
    // was opened, an external save round-tripped through the reader, or -- same-document --
    // a header field spliced a new value into the buffer). See [documentKey]'s KDoc for why
    // these two cases are now handled differently (bugfix, review finding).
    LaunchedEffect(text) {
        if (textArea.text != text) {
            val isNewDocument = isExternalTextChangeANewDocument(lastDocumentKey = lastDocumentKey.value, documentKey = documentKey)
            lastDocumentKey.value = documentKey
            applyExternalTextChangeSilently(
                textArea = textArea,
                newText = text,
                isNewDocument = isNewDocument,
                suppressListenerNotifications = applyingExternalChange,
            )
            canUndoState.value = textArea.canUndo()
            canRedoState.value = textArea.canRedo()
        }
    }

    DisposableEffect(textArea) {
        val listener =
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onChanged()

                override fun removeUpdate(e: DocumentEvent) = onChanged()

                override fun changedUpdate(e: DocumentEvent) = onChanged()

                private fun onChanged() {
                    // Programmatic replace in progress (see `applyingExternalChange`'s KDoc
                    // above) -- this event is one of the transient empty/re-filled states of
                    // that replace, not a real user edit. Don't propagate it outward; the
                    // effect that triggered the replace already updates undo/redo state once
                    // the whole replace has completed.
                    if (applyingExternalChange.get()) return
                    val newText = textArea.text
                    if (newText != currentText) {
                        currentOnTextChange(newText)
                    }
                    canUndoState.value = textArea.canUndo()
                    canRedoState.value = textArea.canRedo()
                }
            }
        textArea.document.addDocumentListener(listener)
        onDispose {
            textArea.document.removeDocumentListener(listener)
            // Report the handle as gone the moment this editor unmounts, so a menu/toolbar
            // cannot keep calling undo()/redo() against an abandoned RSyntaxTextArea.
            onEditorReady(null)
        }
    }

    SwingPanel(
        factory = { RTextScrollPane(textArea) },
        modifier = modifier.testTag(testTag),
    )
}
