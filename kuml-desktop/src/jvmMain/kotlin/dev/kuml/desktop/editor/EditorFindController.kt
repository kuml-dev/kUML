package dev.kuml.desktop.editor

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine

/**
 * Incremental-find state machine against a real [RSyntaxTextArea] (V3.7.5, review fix).
 * Extracted out of [EditorPane]'s Compose closures specifically so it is unit-testable without
 * a Compose UI test harness -- kuml-desktop has none; a plain headless `RSyntaxTextArea`
 * (`java.awt.headless=true` is enough, no visible window required) is a real, sufficient fake
 * here -- see `EditorFindControllerTest`.
 *
 * Two distinct callers drive [find], and conflating them into a single anchor that always
 * advances to the match END on every hit was the actual bug this class fixes (two review
 * findings against the original V3.7.4 find bar):
 *
 *  - **Typing** (every keystroke in the find field, or toggling match-case) passes
 *    `advance = false`: it re-runs from the FIXED [anchor] every time and never moves it.
 *    Otherwise growing a query from "fo" to "foo" resumed searching from the END of the "fo"
 *    match instead of re-checking whether "foo" still matches at the SAME spot -- which skips
 *    straight over the occurrence the user is literally typing out, and can even flip a real
 *    match into a false "no match" once the anchor has wandered past every remaining
 *    occurrence.
 *  - **Explicit navigation** (Enter/Shift+Enter, the prev/next buttons) passes `advance = true`:
 *    it searches starting from the CURRENTLY HIGHLIGHTED match's own boundary in the requested
 *    direction -- read live off the text area's selection (`selectionEnd` forward,
 *    `selectionStart` backward) rather than a separately tracked anchor. A single anchor that
 *    only ever moves to the match END breaks backward search entirely: RSTA's backward search
 *    scans `[0, start)`, and an anchor sitting at the match's own END is still `<=` that bound,
 *    so the search re-finds the SAME occurrence forever (Shift+Enter never appeared to move).
 *    Anchoring backward search at the match's START instead correctly excludes it from `[0,
 *    start)`, so each step lands on a genuinely different, earlier match.
 */
internal class EditorFindController(
    private val textArea: RSyntaxTextArea,
) {
    private var anchor: Int = 0

    /** Resets the search anchor to the current caret position. Call when the find bar opens. */
    fun beginFind() {
        anchor = textArea.caretPosition
    }

    /**
     * Runs one incremental search. Returns `true` if [query] was found (and is now
     * highlighted/selected in the text area).
     *
     * See the class KDoc for what [advance] controls.
     */
    fun find(
        query: String,
        forward: Boolean,
        matchCase: Boolean,
        advance: Boolean,
    ): Boolean {
        val length = textArea.document.length
        val startPos =
            if (advance) {
                if (forward) textArea.selectionEnd else textArea.selectionStart
            } else {
                anchor
            }.coerceIn(0, length)
        textArea.caretPosition = startPos
        val ctx = buildSearchContext(query = query, forward = forward, matchCase = matchCase)
        return SearchEngine.find(textArea, ctx).wasFound()
    }

    /** Clears highlights, leaves the caret at the last match, returns focus to the editor. */
    fun endFind() {
        SearchEngine.markAll(textArea, SearchContext().apply { setMarkAll(false) })
        textArea.requestFocusInWindow()
    }
}
