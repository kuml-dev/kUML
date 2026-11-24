package dev.kuml.layout.bridge.label

import dev.kuml.layout.Size
import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import kotlin.math.floor

/**
 * One wrapped line of an STM edge label: styled [parts] (a short label keeps several parts on one line, a
 * wrapped label has exactly one). [brokenInWord] marks a line that ends in the middle of a word; a hyphen
 * ends every non-last line of a broken word.
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

    /** A single word up to this width (px) is never split; its line is widened instead. */
    public const val WIDE_LINE_MAX_PX: Float = 400f

    /** DoS guard: at most this many characters of one part are processed. */
    private const val MAX_PART_CHARS = 1024

    /** PAL Welle L: a narrower variant of a label is never wrapped to less than this text width (px). */
    public const val MIN_VARIANT_WIDTH_PX: Float = 60f

    /** PAL Welle L: wrap widths of the narrower label variants as fractions of the original wrap width, widest first. */
    private val VARIANT_FACTORS: List<Float> = listOf(0.75f, 0.55f, 0.40f)

    /** Number of characters that fit a line of [maxWidthPx]. */
    public fun maxChars(maxWidthPx: Float): Int = floor(maxWidthPx / CHAR_W).toInt().coerceAtLeast(8)

    /**
     * Wraps [parts] to lines of at most [maxWidthPx]. A label that fits stays on one line; otherwise every part
     * starts on its own line. Inside a part the break order is: at spaces; before `&&` and `||`; after `-`, `/` and
     * `,` (the character stays at the end of the line); after `(` only for a group that does not fit otherwise;
     * and a word up to [WIDE_LINE_MAX_PX] stays whole on a widened line; only a longer word is cut hard, each non-last piece ending with `-`. `)`, `]`
     * and `,` never start a line.
     *
     * [minWordsPerLine] is 3 by default: the wrap width is raised so that every line but the last holds at least three
     * words. The narrower variants of PAL Welle L pass 1 and so give up this rule (never the rule of not splitting a word).
     */
    public fun wrap(
        parts: List<EdgeLabelPart>,
        maxWidthPx: Float = DEFAULT_MAX_WIDTH_PX,
        minWordsPerLine: Int = 3,
    ): List<StmLabelLine> {
        if (parts.isEmpty()) return emptyList()
        val maxC = maxChars(maxWidthPx)
        val joined = parts.joinToString(" ") { it.text }
        if (joined.length <= maxC && joined.length * CHAR_W <= maxWidthPx) return listOf(StmLabelLine(parts = parts))
        val out = ArrayList<StmLabelLine>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            val next = parts.getOrNull(i + 1)
            if (p.kind == EdgeLabelKind.Name && p.text.endsWith(":") && next != null) {
                // The name (`T19:`) sticks to the first word of the next part and never stands alone on a line.
                val wrapped =
                    wrapText(
                        text = next.text.take(MAX_PART_CHARS),
                        maxC = maxC,
                        minWords = minWordsPerLine,
                        prefix = p.text.take(MAX_PART_CHARS),
                    )
                for ((k, wl) in wrapped.withIndex()) {
                    val (line, broken) = wl
                    if (k == 0) {
                        val rest = line.removePrefix(p.text).trim()
                        out +=
                            StmLabelLine(
                                parts = listOfNotNull(p, rest.takeIf { it.isNotEmpty() }?.let { next.copy(text = it) }),
                                brokenInWord = broken,
                            )
                    } else {
                        out += StmLabelLine(parts = listOf(next.copy(text = line)), brokenInWord = broken)
                    }
                }
                i += 2
                continue
            }
            val text = p.text.take(MAX_PART_CHARS)
            for ((line, broken) in wrapText(text = text, maxC = maxC, minWords = minWordsPerLine)) {
                out += StmLabelLine(parts = listOf(p.copy(text = line)), brokenInWord = broken)
            }
            i++
        }
        return out
    }

    /**
     * PAL Welle L: the wrap widths (px, widest first) of the narrower variants of a label that was wrapped to [maxWidthPx]:
     * 0.75, 0.55 and 0.40 times that width, never below [MIN_VARIANT_WIDTH_PX] and never below the longest word of
     * [parts] (a word is never split). Widths that do not end up narrower than [maxWidthPx], and duplicates, are dropped.
     */
    public fun variantWidths(
        parts: List<EdgeLabelPart>,
        maxWidthPx: Float = DEFAULT_MAX_WIDTH_PX,
    ): List<Float> {
        val longest =
            parts.maxOfOrNull { p ->
                p.text
                    .take(MAX_PART_CHARS)
                    .split(' ')
                    .maxOfOrNull { it.length } ?: 0
            } ?: 0
        val floor = maxOf(MIN_VARIANT_WIDTH_PX, longest * CHAR_W)
        return VARIANT_FACTORS.map { maxOf(floor, maxWidthPx * it) }.filter { it < maxWidthPx }.distinct()
    }

    /** Width and height of the box of [lines]. */
    public fun boxSize(lines: List<StmLabelLine>): Size {
        val chars = lines.maxOfOrNull { it.text.length } ?: 0
        return Size(width = chars * CHAR_W + 2 * PAD, height = lines.size * LINE_H + 2 * PAD)
    }

    /**
     * Wraps [text]; with a [prefix] (a transition name) the prefix is glued to the first word. The wrap width is at
     * least wide enough for any three consecutive words (but never above the default maximum width), so every line
     * but the last holds at least three words.
     */
    private fun wrapText(
        text: String,
        maxC: Int,
        minWords: Int = 3,
        prefix: String? = null,
    ): List<Pair<String, Boolean>> {
        val plain = glue(text.split(' ').filter { it.isNotEmpty() })
        val words =
            if (prefix == null) {
                plain
            } else if (plain.isEmpty()) {
                listOf(prefix)
            } else {
                listOf("$prefix ${plain[0]}") + plain.drop(1)
            }
        if (words.isEmpty()) return listOf("" to false)
        if (minWords <= 1) return wrapWords(words = words, maxC = maxC)
        val threeWords =
            words
                .windowed(size = 3, partialWindows = true)
                .filter { w -> w.size == 3 && w.all { it.length <= maxC } }
                .maxOfOrNull { w -> w.sumOf { it.length } + w.size - 1 } ?: 0
        return wrapWords(words = words, maxC = maxOf(maxC, minOf(threeWords, maxChars(DEFAULT_MAX_WIDTH_PX))))
    }

    private fun wrapWords(
        words: List<String>,
        maxC: Int,
    ): List<Pair<String, Boolean>> {
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
                if (atom.length <= maxChars(WIDE_LINE_MAX_PX)) {
                    // A word that fits the absolute maximum is never split: its line grows beyond [maxC] instead.
                    cur.append(atom)
                    continue
                }
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

    /**
     * Last resort for a word longer than [WIDE_LINE_MAX_PX]: pieces of at most [maxC] characters; every piece but
     * the last ends with a `-`.
     */
    private fun hardBreak(
        word: String,
        maxC: Int,
    ): List<String> {
        val pieces = word.chunked((maxC - 1).coerceAtLeast(1))
        return pieces.mapIndexed { i, piece -> if (i < pieces.size - 1) "$piece-" else piece }
    }
}
