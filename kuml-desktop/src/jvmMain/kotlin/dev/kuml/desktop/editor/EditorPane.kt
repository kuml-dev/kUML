package dev.kuml.desktop.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import dev.kuml.desktop.AppState
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.SearchContext

/**
 * Undo/Redo + Find callbacks exposed by a mounted [SyntaxTextEditor]'s underlying `RSyntaxTextArea`
 * (P2/P8, design review). The host (`MainWindow`'s Edit menu / [dev.kuml.desktop.editor.FindBar])
 * doesn't own the Swing text area, so it receives this small handle via `onEditorReady` instead
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

/**
 * Single-file script editor, bound to [AppState.script]/[AppState.isDirty] (V-next,
 * editable-workspace welle: now a thin adapter over the source-agnostic [SyntaxTextEditor],
 * which also backs the OKF document editor — `DocumentEditorPane`).
 *
 * Regression guard: [AppState.isDirty] is set to `true` **before** [AppState.script] is
 * overwritten, and the comparison is against the OLD `state.script` value — exactly the order
 * the pre-extraction `EditorPane` used (`state.isDirty = true` precedes `state.script =
 * newScript`, compared against the pre-assignment value). Reordering this would falsely mark
 * the document dirty on a purely programmatic load (e.g. File ▸ Open): opening a file sets a
 * new `state.script`, which flows into [SyntaxTextEditor] as a new `text` and is synced into
 * the Swing text area; the resulting `DocumentEvent` calls this lambda with `newText` already
 * equal to the freshly loaded `state.script`, so `newText != state.script` is `false` and
 * neither branch fires — dirty stays untouched. Getting the equality check's operand order
 * wrong here is exactly [AppState.isDirty] leaking a false positive.
 */
@Composable
fun EditorPane(
    state: AppState,
    modifier: Modifier = Modifier,
    onEditorReady: (EditorActions?) -> Unit = {},
) {
    SyntaxTextEditor(
        text = state.script,
        onTextChange = { newText ->
            if (newText != state.script) {
                state.isDirty = true
            }
            state.script = newText
        },
        syntaxStyle = SyntaxConstants.SYNTAX_STYLE_KOTLIN,
        modifier = modifier,
        testTag = "kuml-editor",
        onEditorReady = onEditorReady,
    )
}
