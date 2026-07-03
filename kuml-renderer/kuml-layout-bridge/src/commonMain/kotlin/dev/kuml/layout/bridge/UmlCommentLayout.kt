package dev.kuml.layout.bridge

import dev.kuml.layout.Size
import dev.kuml.layout.TextWrap
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
    /** Avg width of an 11pt regular sans-serif char (note body — `kuml-body`). */
    public const val BODY_CHAR_PX: Float = 6.6f

    /** Horizontal padding (left + right) inside the note box. */
    public const val H_PADDING: Float = 16f

    /** Vertical padding (top + bottom) inside the note box. */
    public const val V_PADDING: Float = 16f

    /** Line height for note body text. */
    public const val LINE_H: Float = 15f

    /** Side length of the folded top-right corner ("dog-ear"). */
    public const val DOG_EAR_SIZE: Float = 14f

    /** Default note width when the body is empty or very short. */
    public const val DEFAULT_W: Float = 140f

    /** Default note height when the body is empty or very short. */
    public const val DEFAULT_H: Float = 60f

    /**
     * Maximum width a note is allowed to grow to before wrapping kicks in
     * more aggressively. Keeps very long single-paragraph bodies from
     * producing an absurdly wide, short box.
     */
    public const val MAX_W: Float = 260f

    /**
     * Wraps [body] into display lines, honouring explicit newlines as hard
     * paragraph breaks and word-wrapping each paragraph to [maxInnerWidth].
     *
     * Both [UmlContentSizeProvider] (sizing) and the SVG renderer (drawing)
     * must call this with the same [maxInnerWidth] to stay in sync — see the
     * class KDoc.
     */
    public fun wrapBody(
        body: String,
        maxInnerWidth: Float = MAX_W - H_PADDING,
    ): List<String> =
        body
            .split("\n")
            .flatMap { paragraph ->
                if (paragraph.isBlank()) {
                    listOf("")
                } else {
                    TextWrap.wrapToWidth(paragraph, maxInnerWidth, BODY_CHAR_PX)
                }
            }

    /** Computes the note box [Size] for [comment], per [wrapBody] + padding rules. */
    public fun sizeOf(comment: UmlComment): Size {
        val lines = wrapBody(comment.body)
        val widestLine = lines.maxOfOrNull { it.length * BODY_CHAR_PX } ?: 0f
        val w = maxOf(DEFAULT_W, minOf(MAX_W, widestLine + H_PADDING))
        val h = maxOf(DEFAULT_H, lines.size * LINE_H + V_PADDING)
        return Size(w, h)
    }
}
