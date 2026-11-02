package dev.kuml.layout.bridge

import dev.kuml.core.model.note.KumlNoteLimits
import dev.kuml.core.model.note.NoteKind
import dev.kuml.layout.Size
import dev.kuml.layout.TextWrap

/** Frame style of a note symbol. */
public enum class NoteFrameStyle {
    /** Rectangle with a folded top-right corner (UML note, SysML 2 comment, ERM note). */
    DOG_EAR,

    /** Open square bracket on the left (BPMN text annotation). */
    BRACKET,
}

/** Display text of a note after truncation and wrapping. */
public data class NoteText(
    val lines: List<String>,
    val truncated: Boolean,
)

/**
 * Shared layout contract for note symbols across UML, SysML 2, BPMN and ERM.
 *
 * Sizing and rendering must call [displayText] with identical parameters so the
 * box is sized for exactly the lines that are drawn.
 */
public object NoteLayout {
    /** Avg width of an 11pt regular sans-serif char (note body). */
    public const val BODY_CHAR_PX: Float = 6.6f

    /** Horizontal padding (left + right) inside the note box. */
    public const val H_PADDING: Float = 16f

    /** Vertical padding (top + bottom) inside the note box. */
    public const val V_PADDING: Float = 16f

    /** Line height for note body text. */
    public const val LINE_H: Float = 15f

    /** Side length of the folded top-right corner. */
    public const val DOG_EAR_SIZE: Float = 14f

    /** Arm length of the BPMN bracket. */
    public const val BRACKET_ARM: Float = 12f

    /** Default note width for empty or very short bodies. */
    public const val DEFAULT_W: Float = 140f

    /** Default note height for empty or very short bodies. */
    public const val DEFAULT_H: Float = 60f

    /** Maximum note width. */
    public const val MAX_W: Float = 260f

    /** Maximum note width in state-machine diagrams (PAL wave 2D-b): text wraps at this width minus the padding. */
    public const val STM_MAX_W: Float = 220f

    /** Side length of the kind symbol (px). */
    public const val ICON_SIZE: Float = 16f

    /** Gap between the symbol and the text (px). */
    public const val ICON_GAP: Float = 6f

    /** Width of the symbol column (symbol + gap) that a kind symbol takes from the text area. */
    public const val ICON_COL: Float = 22f

    /** Y offset of the symbol inside the note box. */
    public const val ICON_Y: Float = V_PADDING / 2f

    /** Width taken from the text area by the symbol of [kind] (0 for [NoteKind.NOTE]). */
    public fun iconColumn(kind: NoteKind): Float = if (kind.hasIcon) ICON_COL else 0f

    /** X position where the text starts. Unchanged for [NoteKind.NOTE]; with a symbol the text moves right by [ICON_COL]. */
    public fun textInset(
        frame: NoteFrameStyle,
        kind: NoteKind,
    ): Float {
        val base = if (frame == NoteFrameStyle.BRACKET) H_PADDING / 2f + 8f else H_PADDING / 2f
        return if (kind.hasIcon) base + ICON_COL else base
    }

    /** X position of the symbol: directly left of the text column. */
    public fun iconX(
        frame: NoteFrameStyle,
        kind: NoteKind,
    ): Float = textInset(frame = frame, kind = kind) - ICON_COL

    /**
     * Truncates [body] to [KumlNoteLimits.MAX_DISPLAY_CHARS] (before any line
     * splitting, so a body made only of newlines cannot create tens of
     * thousands of paragraphs), wraps it, hard-splits over-long words and caps
     * the result at [KumlNoteLimits.MAX_DISPLAY_LINES]. A truncated text ends
     * on [KumlNoteLimits.ELLIPSIS]. [maxInnerWidth] is the width of the box minus the padding; a [kind] with a
     * symbol takes [ICON_COL] of it.
     */
    public fun displayText(
        body: String,
        maxInnerWidth: Float = MAX_W - H_PADDING,
        kind: NoteKind = NoteKind.NOTE,
    ): NoteText {
        var truncated = body.length > KumlNoteLimits.MAX_DISPLAY_CHARS
        val text = if (truncated) body.take(KumlNoteLimits.MAX_DISPLAY_CHARS) else body
        val maxInnerWidth = maxInnerWidth - iconColumn(kind)
        val chunk = maxOf(1, (maxInnerWidth / BODY_CHAR_PX).toInt())

        val lines = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (lines.size > KumlNoteLimits.MAX_DISPLAY_LINES) break
            if (paragraph.isBlank()) {
                lines.add("")
            } else {
                for (wrapped in TextWrap.wrapToWidth(text = paragraph, maxWidthPx = maxInnerWidth, charPx = BODY_CHAR_PX)) {
                    if (wrapped.length <= chunk) lines.add(wrapped) else lines.addAll(wrapped.chunked(chunk))
                }
            }
        }

        var result: List<String> = lines
        if (lines.size > KumlNoteLimits.MAX_DISPLAY_LINES) {
            truncated = true
            result = lines.take(KumlNoteLimits.MAX_DISPLAY_LINES)
        }
        if (truncated && result.isNotEmpty()) {
            val last = result.last()
            val withEllipsis =
                if (last.length + 1 <= chunk) {
                    last + KumlNoteLimits.ELLIPSIS
                } else {
                    last.take(chunk - 1) + KumlNoteLimits.ELLIPSIS
                }
            result = result.dropLast(1) + withEllipsis
        }
        return NoteText(lines = result, truncated = truncated)
    }

    /** Computes the note box [Size] for [body]. Brackets and dog-ears are sized identically. */
    @Suppress("UNUSED_PARAMETER")
    public fun sizeOf(
        body: String,
        frame: NoteFrameStyle = NoteFrameStyle.DOG_EAR,
        maxWidth: Float = MAX_W,
        kind: NoteKind = NoteKind.NOTE,
    ): Size {
        val lines = displayText(body = body, maxInnerWidth = maxWidth - H_PADDING, kind = kind).lines
        val widestLine = lines.maxOfOrNull { it.length * BODY_CHAR_PX } ?: 0f
        val w = maxOf(minOf(DEFAULT_W, maxWidth), minOf(maxWidth, widestLine + H_PADDING + iconColumn(kind)))
        val h =
            if (kind.hasIcon) {
                maxOf(DEFAULT_H, lines.size * LINE_H + V_PADDING, ICON_SIZE + V_PADDING)
            } else {
                maxOf(DEFAULT_H, lines.size * LINE_H + V_PADDING)
            }
        return Size(width = w, height = h)
    }

    /** True if [body] is cut by the display limits. */
    public fun isTruncated(
        body: String,
        kind: NoteKind = NoteKind.NOTE,
    ): Boolean = displayText(body = body, kind = kind).truncated
}
