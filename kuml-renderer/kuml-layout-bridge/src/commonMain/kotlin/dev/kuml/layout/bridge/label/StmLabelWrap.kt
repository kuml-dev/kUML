package dev.kuml.layout.bridge.label

import dev.kuml.layout.Size
import dev.kuml.sysml2.edge.EdgeLabelPart
import kotlin.math.floor

/**
 * One wrapped line of an STM edge label: styled [parts] (a short label keeps several parts on one line, a
 * wrapped label has exactly one). [brokenInWord] marks a line that ends in the middle of a word; no character was
 * added, so the lines of a broken word concatenated give the original word.
 */
public data class StmLabelLine(
    val parts: List<EdgeLabelPart>,
    val brokenInWord: Boolean = false,
) {
    /** The text of the line as drawn. */
    val text: String get() = parts.joinToString(" ") { it.text }
}

/**
 * Text wrapping and box sizes of SysML 2 state-transition labels (PAL wave 2D-a). Nothing is ever cut: a label that
 * exceeds the maximum width wraps to as many lines as it needs and the full text stays in the SVG. Geometry uses
 * [CHAR_W], never measured text widths, so it is font independent. The wrap lives in the layout bridge (wave F), so
 * the layout can reserve room for the label before the routes exist and the renderer draws exactly what was
 * planned; the SVG module's `StmLabelPlacer` delegates here.
 */
public object StmLabelWrap {
    public const val CHAR_W: Float = Sysml2LabelSizing.CHAR_PX_EDGE_LABEL
    public const val LINE_H: Float = 12f
    public const val PAD: Float = 3f

    /** Default wrap width in px (`layout { labelMaxWidth = ... }` changes it, range 120..400). */
    public const val DEFAULT_MAX_WIDTH_PX: Float = 220f

    /** DoS guard: at most this many characters of one part are processed. */
    private const val MAX_PART_CHARS = 1024

    /** Number of characters that fit a line of [maxWidthPx]. */
    public fun maxChars(maxWidthPx: Float): Int = floor(maxWidthPx / CHAR_W).toInt().coerceAtLeast(8)

    /**
     * Wraps [parts] to lines of at most [maxWidthPx]. A label that fits stays on one line; otherwise every part
     * starts on its own line. Inside a part the break order is: at spaces; before `&&` and `||`; after `-`, `/` and
     * `,` (the character stays at the end of the line); after `(` only for a group that does not fit otherwise;
     * and only for a word that is still too long hard inside the word, without an inserted character. `)`, `]`
     * and `,` never start a line.
     */
    public fun wrap(
        parts: List<EdgeLabelPart>,
        maxWidthPx: Float = DEFAULT_MAX_WIDTH_PX,
    ): List<StmLabelLine> {
        if (parts.isEmpty()) return emptyList()
        val maxC = maxChars(maxWidthPx)
        val joined = parts.joinToString(" ") { it.text }
        if (joined.length <= maxC && joined.length * CHAR_W <= maxWidthPx) return listOf(StmLabelLine(parts = parts))
        val out = ArrayList<StmLabelLine>()
        for (p in parts) {
            val text = p.text.take(MAX_PART_CHARS)
            for ((line, broken) in wrapText(text = text, maxC = maxC)) {
                out += StmLabelLine(parts = listOf(p.copy(text = line)), brokenInWord = broken)
            }
        }
        return out
    }

    /** Width and height of the box of [lines]. */
    public fun boxSize(lines: List<StmLabelLine>): Size {
        val chars = lines.maxOfOrNull { it.text.length } ?: 0
        return Size(width = chars * CHAR_W + 2 * PAD, height = lines.size * LINE_H + 2 * PAD)
    }

    private fun wrapText(
        text: String,
        maxC: Int,
    ): List<Pair<String, Boolean>> {
        val words = glue(text.split(' ').filter { it.isNotEmpty() })
        if (words.isEmpty()) return listOf("" to false)
        val lines = ArrayList<Pair<String, Boolean>>()
        val cur = StringBuilder()

        fun flush() {
            if (cur.isNotEmpty()) lines += cur.toString() to false
            cur.setLength(0)
        }

        fun place(
            unit: String,
            spaceBefore: Boolean,
        ) {
            val need = (if (cur.isEmpty() || !spaceBefore) 0 else 1) + unit.length
            if (cur.isNotEmpty() && cur.length + need > maxC) flush()
            if (cur.isNotEmpty() && spaceBefore) cur.append(' ')
            cur.append(unit)
        }
        for (word in words) {
            if (word.length <= maxC) {
                place(unit = word, spaceBefore = true)
                continue
            }
            // A glued `/ effect` that does not fit as a whole is un-glued: the `/` stays, the effect wraps on.
            var rest = word
            if (word.startsWith("/ ")) {
                place(unit = "/", spaceBefore = true)
                rest = word.substring(2)
            }
            for ((k, atom) in softAtoms(word = rest, maxC = maxC).withIndex()) {
                if (atom.length <= maxC) {
                    place(unit = atom, spaceBefore = k == 0)
                    continue
                }
                if (cur.isNotEmpty()) flush()
                val chunks = hardBreak(word = atom, maxC = maxC)
                for (i in 0 until chunks.size - 1) lines += chunks[i] to true
                cur.append(chunks.last())
            }
        }
        flush()
        return lines
    }

    /** Words in front of which a `/` stands are glued to it (`/ effect`). */
    private fun glue(words: List<String>): List<String> {
        if (words.size >= 2 && words[0] == "/") return listOf("/ " + words[1]) + words.drop(2)
        return words
    }

    /**
     * Break opportunities inside one overlong word. First level: before `&&` / `||` and after `-`, `/` and `,`.
     * An atom that is still longer than [maxC] is split a second time after a `(` that has content. `)`, `]`, `,`
     * and `;` are never separated from the atom before them.
     */
    private fun softAtoms(
        word: String,
        maxC: Int,
    ): List<String> =
        splitAt(text = word) { i ->
            val prev = word[i - 1]
            val next = word[i]
            val afterSeparator = (prev == '-' || prev == '/' || prev == ',') && next !in NO_LINE_START
            afterSeparator || word.startsWith("&&", i) || word.startsWith("||", i)
        }.flatMap { atom ->
            if (atom.length <= maxC) {
                listOf(atom)
            } else {
                splitAt(text = atom) { i -> atom[i - 1] == '(' && atom[i] !in NO_LINE_START }
            }
        }

    private const val NO_LINE_START = ")];,"

    private fun splitAt(
        text: String,
        breakBefore: (Int) -> Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 1 until text.length) {
            if (breakBefore(i)) {
                out += text.substring(start, i)
                start = i
            }
        }
        out += text.substring(start)
        return out
    }

    /** Last resort: cuts a word into pieces of [maxC] characters; no character is added, the pieces give the word. */
    private fun hardBreak(
        word: String,
        maxC: Int,
    ): List<String> = word.chunked(maxC)
}
