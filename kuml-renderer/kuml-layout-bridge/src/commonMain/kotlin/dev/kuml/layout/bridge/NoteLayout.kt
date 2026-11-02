package dev.kuml.layout.bridge

import dev.kuml.core.model.note.KumlNoteLimits
import dev.kuml.core.model.note.NoteBlock
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NoteListItem
import dev.kuml.core.model.note.NoteMarkup
import dev.kuml.core.model.note.NoteSpan
import dev.kuml.layout.Size
import dev.kuml.layout.TextWrap

/** Frame style of a note symbol. */
public enum class NoteFrameStyle {
    /** Rectangle with a folded top-right corner (UML note, SysML 2 comment, ERM note). */
    DOG_EAR,

    /** Open square bracket on the left (BPMN text annotation). */
    BRACKET,
}

/**
 * One wrapped line of a note with formatted text. [indentPx] is the extra offset of the text from the
 * note's text inset (list items), [marker] the list marker drawn at the text inset on the first line of a
 * list item (`null` otherwise).
 */
public data class StyledLine(
    val indentPx: Float,
    val marker: String?,
    val spans: List<NoteSpan>,
)

/**
 * Display text of a note after truncation and wrapping. [styledLines] is `null` for a note without markup
 * (the unchanged plain-text path); otherwise it parallels [lines], which then holds the visible text of
 * each line without the list marker.
 */
public data class NoteText(
    val lines: List<String>,
    val truncated: Boolean,
    val styledLines: List<StyledLine>? = null,
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

    /** Width factor of a bold span (bold glyphs are wider). */
    public const val BOLD_FACTOR: Float = 1.10f

    /** Width factor of a code span (the monospace face is wider than the body face). */
    public const val CODE_FACTOR: Float = 1.10f

    /** Hanging indent of a bullet list item. */
    public const val BULLET_INDENT: Float = 14f

    /** Marker of a bullet list item. */
    public const val BULLET_MARKER: String = "\u2022"

    /** Gap between a number marker and the text of a numbered list item. */
    public const val NUMBER_MARKER_GAP: Float = 5f

    /** Width factor of [span] relative to [BODY_CHAR_PX]. */
    public fun styleFactor(span: NoteSpan): Float =
        if (span.code) {
            CODE_FACTOR
        } else if (span.bold) {
            BOLD_FACTOR
        } else {
            1f
        }

    /** Estimated width of [span]. */
    public fun spanWidth(span: NoteSpan): Float = span.text.length * BODY_CHAR_PX * styleFactor(span)

    /** Estimated width of [line] including its indent. */
    public fun lineWidth(line: StyledLine): Float = line.indentPx + line.spans.fold(0f) { acc, span -> acc + spanWidth(span) }

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
        val truncated = body.length > KumlNoteLimits.MAX_DISPLAY_CHARS
        val text = if (truncated) body.take(KumlNoteLimits.MAX_DISPLAY_CHARS) else body
        val inner = maxInnerWidth - iconColumn(kind)
        val doc = NoteMarkup.parse(text)
        return if (doc.isPlain) {
            displayPlain(text = text, maxInnerWidth = inner, wasTruncated = truncated)
        } else {
            displayStyled(blocks = doc.blocks, maxInner = inner, wasTruncated = truncated)
        }
    }

    private fun displayPlain(
        text: String,
        maxInnerWidth: Float,
        wasTruncated: Boolean,
    ): NoteText {
        var truncated = wasTruncated
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

    private class Cell(
        val ch: Char,
        val style: NoteSpan,
    ) {
        val width: Float get() = BODY_CHAR_PX * styleFactor(style)
    }

    private fun sameStyle(
        a: NoteSpan,
        b: NoteSpan,
    ): Boolean = a.bold == b.bold && a.italic == b.italic && a.code == b.code && a.href == b.href

    /** Flattens [spans] into cells; whitespace runs collapse to one space and the ends are trimmed, as in [TextWrap]. */
    private fun cellsOf(spans: List<NoteSpan>): List<Cell> {
        val cells = ArrayList<Cell>()
        for (span in spans) {
            for (ch in span.text) {
                if (ch.isWhitespace()) {
                    if (cells.isNotEmpty() && cells.last().ch != ' ') cells.add(Cell(ch = ' ', style = span))
                } else {
                    cells.add(Cell(ch = ch, style = span))
                }
            }
        }
        if (cells.isNotEmpty() && cells.last().ch == ' ') cells.removeAt(cells.size - 1)
        return cells
    }

    private fun groupCells(cells: List<Cell>): List<NoteSpan> {
        val out = ArrayList<NoteSpan>()
        val buf = StringBuilder()
        var style: NoteSpan? = null
        for (cell in cells) {
            val cur = style
            if (cur != null && !sameStyle(a = cur, b = cell.style)) {
                out.add(cur.copy(text = buf.toString()))
                buf.setLength(0)
            }
            style = cell.style
            buf.append(cell.ch)
        }
        style?.let { out.add(it.copy(text = buf.toString())) }
        return out
    }

    /** Greedy wrap of [cells] into lines of at most [avail] px; a word wider than that is split by width (at least one char per line). */
    private fun wrapCells(
        cells: List<Cell>,
        avail: Float,
    ): List<List<Cell>> {
        val lines = ArrayList<List<Cell>>()
        var line = ArrayList<Cell>()
        var width = 0f

        fun finish() {
            lines.add(line)
            line = ArrayList()
            width = 0f
        }
        var i = 0
        while (i < cells.size) {
            if (cells[i].ch == ' ') {
                i++
                continue
            }
            var j = i
            var wordW = 0f
            while (j < cells.size && cells[j].ch != ' ') {
                wordW += cells[j].width
                j++
            }
            val space = if (i > 0) cells[i - 1] else null
            if (line.isNotEmpty() && space != null && width + space.width + wordW <= avail) {
                line.add(space)
                width += space.width
            } else if (line.isNotEmpty()) {
                finish()
            }
            if (wordW <= avail) {
                for (k in i until j) line.add(cells[k])
                width += wordW
            } else {
                for (k in i until j) {
                    if (line.isNotEmpty() && width + cells[k].width > avail) finish()
                    line.add(cells[k])
                    width += cells[k].width
                }
            }
            i = j
        }
        if (line.isNotEmpty()) finish()
        return lines
    }

    private fun isOrdered(block: NoteBlock): Boolean = block is NoteListItem && block.ordered

    private fun displayStyled(
        blocks: List<NoteBlock>,
        maxInner: Float,
        wasTruncated: Boolean,
    ): NoteText {
        // Width of the number column per run of consecutive numbered items.
        val markerLen = IntArray(blocks.size)
        var idx = 0
        while (idx < blocks.size) {
            if (isOrdered(blocks[idx])) {
                var end = idx
                while (end + 1 < blocks.size && isOrdered(blocks[end + 1])) end++
                val longest = (idx..end).maxOf { "${(blocks[it] as NoteListItem).number}.".length }
                for (k in idx..end) markerLen[k] = longest
                idx = end + 1
            } else {
                idx++
            }
        }

        val styled = ArrayList<StyledLine>()
        for ((bi, block) in blocks.withIndex()) {
            if (styled.size > KumlNoteLimits.MAX_DISPLAY_LINES) break
            var indent = 0f
            var marker: String? = null
            if (block is NoteListItem) {
                if (block.ordered) {
                    indent = markerLen[bi] * BODY_CHAR_PX + NUMBER_MARKER_GAP
                    marker = "${block.number}."
                } else {
                    indent = BULLET_INDENT
                    marker = BULLET_MARKER
                }
            }
            val wrapped = wrapCells(cells = cellsOf(block.spans), avail = maxInner - indent)
            if (wrapped.isEmpty()) {
                styled.add(StyledLine(indentPx = indent, marker = marker, spans = emptyList()))
            } else {
                wrapped.forEachIndexed { n, cells ->
                    styled.add(StyledLine(indentPx = indent, marker = if (n == 0) marker else null, spans = groupCells(cells)))
                }
            }
        }

        var truncated = wasTruncated
        var result: List<StyledLine> = styled
        if (styled.size > KumlNoteLimits.MAX_DISPLAY_LINES) {
            truncated = true
            result = styled.take(KumlNoteLimits.MAX_DISPLAY_LINES)
        }
        if (truncated && result.isNotEmpty()) {
            result = result.dropLast(1) + withEllipsis(line = result.last(), maxInner = maxInner)
        }
        return NoteText(lines = result.map { line -> line.spans.joinToString("") { it.text } }, truncated = truncated, styledLines = result)
    }

    private fun withEllipsis(
        line: StyledLine,
        maxInner: Float,
    ): StyledLine {
        var spans = line.spans
        val ellipsis = NoteSpan(text = KumlNoteLimits.ELLIPSIS)
        while (spans.isNotEmpty() && lineWidth(StyledLine(indentPx = line.indentPx, marker = null, spans = spans + ellipsis)) > maxInner) {
            val last = spans.last()
            spans = if (last.text.length <= 1) spans.dropLast(1) else spans.dropLast(1) + last.copy(text = last.text.dropLast(1))
        }
        return line.copy(spans = spans + ellipsis)
    }

    /** Computes the note box [Size] for [body]. Brackets and dog-ears are sized identically. */
    @Suppress("UNUSED_PARAMETER")
    public fun sizeOf(
        body: String,
        frame: NoteFrameStyle = NoteFrameStyle.DOG_EAR,
        maxWidth: Float = MAX_W,
        kind: NoteKind = NoteKind.NOTE,
    ): Size {
        val text = displayText(body = body, maxInnerWidth = maxWidth - H_PADDING, kind = kind)
        val lines = text.lines
        val widestLine =
            text.styledLines?.let { styled -> styled.maxOfOrNull { lineWidth(it) } }
                ?: lines.maxOfOrNull { it.length * BODY_CHAR_PX }
                ?: 0f
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
