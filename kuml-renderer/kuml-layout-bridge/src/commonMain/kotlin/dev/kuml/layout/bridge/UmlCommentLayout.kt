package dev.kuml.layout.bridge

import dev.kuml.layout.Size
import dev.kuml.uml.UmlComment

/**
 * Layout constants shared between [UmlContentSizeProvider] (which measures a
 * [UmlComment]'s box size) and the SVG note renderer
 * (`dev.kuml.io.svg.uml.renderUmlComment`), analogous to the
 * `C4DescriptionLayout` contract in `kuml-io-svg`: identical
 * `(text, maxWidthPx, charPx)` triples must yield identical wrapped-line
 * counts on both sides, otherwise the box is sized for N lines but the
 * renderer draws N±1 — text overflows or the box has dead space.
 *
 * The UML note symbol is a rectangle with a folded top-right corner
 * ("dog-ear"). [DOG_EAR_SIZE] is the side length of the folded triangle.
 */
public object UmlCommentLayout {
    /** Avg width of an 11pt regular sans-serif char (note body, `kuml-body`). */
    public const val BODY_CHAR_PX: Float = NoteLayout.BODY_CHAR_PX

    /** Horizontal padding (left + right) inside the note box. */
    public const val H_PADDING: Float = NoteLayout.H_PADDING

    /** Vertical padding (top + bottom) inside the note box. */
    public const val V_PADDING: Float = NoteLayout.V_PADDING

    /** Line height for note body text. */
    public const val LINE_H: Float = NoteLayout.LINE_H

    /** Side length of the folded top-right corner ("dog-ear"). */
    public const val DOG_EAR_SIZE: Float = NoteLayout.DOG_EAR_SIZE

    /** Default note width when the body is empty or very short. */
    public const val DEFAULT_W: Float = NoteLayout.DEFAULT_W

    /** Default note height when the body is empty or very short. */
    public const val DEFAULT_H: Float = NoteLayout.DEFAULT_H

    /** Maximum note width. */
    public const val MAX_W: Float = NoteLayout.MAX_W

    /**
     * Wraps [body] into display lines; delegates to [NoteLayout.displayText], so
     * the shared display limits (2000 chars, 30 lines, hard word break) apply.
     */
    public fun wrapBody(
        body: String,
        maxInnerWidth: Float = MAX_W - H_PADDING,
    ): List<String> = NoteLayout.displayText(body = body, maxInnerWidth = maxInnerWidth).lines

    /** Computes the note box [Size] for [comment]. */
    public fun sizeOf(comment: UmlComment): Size = NoteLayout.sizeOf(body = comment.body)
}
