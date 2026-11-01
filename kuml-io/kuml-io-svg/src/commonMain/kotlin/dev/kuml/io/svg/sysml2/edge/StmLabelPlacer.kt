package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.edge.EdgeLabelPart
import kotlin.math.floor
import kotlin.math.max

/** Horizontal alignment of the text inside a placed label box. */
internal enum class StmLabelAnchor { Left, Right, Center }

/**
 * One wrapped line of an STM edge label: styled [parts] (a short label keeps several parts on one line, a
 * wrapped label has exactly one). [hyphenated] marks a line that ends in a `-` inserted by a hard break inside a word.
 */
internal data class StmLabelLine(
    val parts: List<EdgeLabelPart>,
    val hyphenated: Boolean = false,
) {
    /** The text of the line as drawn. */
    val text: String get() = parts.joinToString(" ") { it.text }
}

/** A placed, wrapped STM edge label: [lines] of styled parts centred on ([cx], [cy]) inside a [w] x [h] box. */
internal data class StmLabelBlock(
    val lines: List<StmLabelLine>,
    val fullText: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val anchor: StmLabelAnchor = StmLabelAnchor.Center,
    /** Id of the placed label (the edge id); written to `data-label-id`. */
    val labelId: String = "",
)

/** Kind of a node-like obstacle for the label plan. */
internal enum class StmObstacleKind { Simple, Composite, Region }

/**
 * A node-like obstacle in final canvas coordinates. [Simple][StmObstacleKind.Simple] states and
 * pseudo-states block their whole rectangle. A [Composite][StmObstacleKind.Composite] contributes its frame,
 * header band ([headerBottom]), [dividers] and [regionNameRects]. A [Region][StmObstacleKind.Region] is not drawn
 * on its own and blocks nothing (its name and separators arrive via the composite).
 */
internal data class StmObstacleNode(
    val id: String,
    val kind: StmObstacleKind,
    val rect: Rect,
    val headerBottom: Float? = null,
    val regionNameRects: List<Rect> = emptyList(),
    val dividers: List<Pair<Point, Point>> = emptyList(),
)

/**
 * Text wrapping and box sizes of SysML 2 state-transition labels (PAL wave 2D-a). Nothing is ever cut: a label that
 * exceeds the maximum width wraps to as many lines as it needs and the full text stays in the SVG. Placement itself
 * is done by `Sysml2DiagramLabelPlan.planStmLabels`. Geometry uses [CHAR_W], never measured text widths, so it is
 * font independent.
 */
internal object StmLabelPlacer {
    const val CHAR_W = 7.2f
    const val LINE_H = 12f
    const val PAD = 3f

    /** Default wrap width in px (`layout { labelMaxWidth = ... }` changes it, range 120..400). */
    const val DEFAULT_MAX_WIDTH_PX = 220f

    /** DoS guard: at most this many characters of one part are processed. */
    private const val MAX_PART_CHARS = 1024

    /** Number of characters that fit a line of [maxWidthPx]. */
    fun maxChars(maxWidthPx: Float): Int = floor(maxWidthPx / CHAR_W).toInt().coerceAtLeast(8)

    /**
     * Wraps [parts] to lines of at most [maxWidthPx]. A label that fits stays on one line; otherwise every part
     * starts on its own line. Inside a part the break order is: at spaces, after `,` `(` and around `&&` `||`
     * `and` `or`, and only for a word that is still too long hard inside the word with a trailing `-`.
     */
    fun wrap(
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
            for ((line, hyphen) in wrapText(text = text, maxC = maxC)) {
                out += StmLabelLine(parts = listOf(p.copy(text = line)), hyphenated = hyphen)
            }
        }
        return out
    }

    /** Width and height of the box of [lines]. */
    fun boxSize(lines: List<StmLabelLine>): Size {
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
            for ((k, atom) in softAtoms(word).withIndex()) {
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

    /** Break opportunities inside one overlong word: after `,` and `(` and around `&&` / `||`. */
    private fun softAtoms(word: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            cur.append(c)
            val two = if (i + 1 < word.length) word.substring(i, i + 2) else ""
            if (two == "&&" || two == "||") {
                // break before the operator
                cur.setLength(cur.length - 1)
                if (cur.isNotEmpty()) out += cur.toString()
                cur.setLength(0)
                out += two
                i += 2
                continue
            }
            if (c == ',' || c == '(') {
                out += cur.toString()
                cur.setLength(0)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun hardBreak(
        word: String,
        maxC: Int,
    ): List<String> {
        val step = max(1, maxC - 1)
        val out = ArrayList<String>()
        var i = 0
        while (word.length - i > maxC) {
            out += word.substring(i, i + step) + "-"
            i += step
        }
        out += word.substring(i)
        return out
    }
}
