@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * PAL Welle X4: a note with a placement hint ABOVE or BELOW, as measured on the rendered SVG (the final layout, never glyph
 * metrics). [box] is the box of the anchor(s): the rectangle of an anchored node, or the attach point of an anchor line
 * that ends on an edge.
 */
internal data class HintNote(
    val noteId: String,
    val hint: String,
    val rect: SvgRect,
    val box: SvgRect,
    /** Length of the anchor line(s) of the note, summed. */
    val leaderLength: Double,
    /**
     * Sequence diagram: the note lives in a margin column and the hint means "clear of the anchor row" (Nacharbeit 102,
     * `SeqNotePlacer`, row gap 12 px); the horizontal overlap is not asked for there.
     */
    val seq: Boolean = false,
)

/** Per diagram result of [NoteHintClearance.report]. */
internal class NoteHintReport(
    val notes: List<HintNote>,
    /** The union of all non-note node boxes. */
    val content: SvgRect?,
    val svgWidth: Double,
) {
    val hinted: Int get() = notes.size
    val honoured: Int get() = notes.count { NoteHintClearance.honoured(it) }

    /** Only the ABOVE / BELOW hints (the gate of this wave); LEFT and RIGHT are measured as a negative control. */
    val hintedAb: Int get() = notes.count { it.hint == "ABOVE" || it.hint == "BELOW" }
    val honouredAb: Int get() = notes.count { (it.hint == "ABOVE" || it.hint == "BELOW") && NoteHintClearance.honoured(it) }

    /** H2: pixels a hinted note adds to the width (rectangle entirely outside the x range of the content). */
    val widthPx: Double
        get() = content?.let { c -> notes.sumOf { NoteHintClearance.widthContribution(it, c) } } ?: 0.0

    /** H2: width of the diagram divided by the width of the content without notes. */
    val widthRatio: Double get() = content?.let { svgWidth / it.w } ?: 1.0

    /** H3: honoured notes whose leader is longer than the distance anchor to content edge plus stack gap plus 24 px. */
    val longLeaders: List<HintNote>
        get() = content?.let { c -> notes.filter { NoteHintClearance.honoured(it) && !NoteHintClearance.leaderOk(it, c) } } ?: emptyList()
}

/**
 * PAL Welle X4 detectors for the placement hints ABOVE and BELOW (SysML 2, all diagram kinds that anchor notes with
 * `note-link` groups).
 *
 * - H1 honoured: ABOVE: the note rectangle ends above the anchor box (bottom at most the box top) and overlaps it by at
 *   least 1 px horizontally (an anchor point must lie within the note's x range); BELOW analogous. This is the strict
 *   definition of `NotePlacer.sideOfStrict` (the W004 report) and may not drift from it.
 * - H2 widening: width of the diagram against the content without notes; per note the pixels it adds on its own.
 * - H3 leader: an honoured note's anchor line is at most (anchor to content edge) + 12 + 24 px long.
 */
internal object NoteHintClearance {
    const val MIN_OVERLAP = 1.0
    const val STACK_GAP = 12.0
    const val LEADER_SLACK = 24.0
    const val SEQ_ROW_GAP = 12.0
    private const val EPS = 0.5

    /** Hints of the script by note id: only `comment(..., id = "x", ..., placement = NotePlacement.ABOVE)` blocks. */
    fun hintsOf(script: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in script.split("comment(").drop(1)) {
            val block = chunk.substringBefore("\n    )").substringBefore("\n)")
            val id = Regex("""\bid\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: continue
            val hint = Regex("""\bplacement\s*=\s*NotePlacement\.(\w+)""").find(block)?.groupValues?.get(1) ?: continue
            out[id] = hint
        }
        return out
    }

    private fun overlapAlong(
        rect: SvgRect,
        box: SvgRect,
        horizontal: Boolean,
    ): Double =
        if (horizontal) {
            min(rect.right, box.right) - max(rect.x, box.x)
        } else {
            min(rect.bottom, box.bottom) - max(rect.y, box.y)
        }

    fun honoured(
        n: HintNote,
        minOverlap: Double = MIN_OVERLAP,
    ): Boolean {
        val horizontalAxis = n.hint == "ABOVE" || n.hint == "BELOW"
        if (n.seq) {
            return when (n.hint) {
                "ABOVE" -> n.rect.bottom <= n.box.y - SEQ_ROW_GAP + EPS
                "BELOW" -> n.rect.y >= n.box.bottom + SEQ_ROW_GAP - EPS
                else -> true
            }
        }
        val extent = if (horizontalAxis) n.box.w else n.box.h
        val overlap = overlapAlong(n.rect, n.box, horizontalAxis)
        // A point anchor (extent 0) only has to lie inside the range of the note.
        val along = overlap >= min(minOverlap, extent) - 1e-9 && overlap >= 0.0
        val onSide =
            when (n.hint) {
                "ABOVE" -> n.rect.bottom <= n.box.y + EPS
                "BELOW" -> n.rect.y >= n.box.bottom - EPS
                "LEFT" -> n.rect.right <= n.box.x + EPS
                "RIGHT" -> n.rect.x >= n.box.right - EPS
                else -> true
            }
        return onSide && along
    }

    /** Pixels the note adds to the width: its part outside the x range of [content] when it lies entirely outside. */
    fun widthContribution(
        n: HintNote,
        content: SvgRect,
    ): Double {
        if (n.hint != "ABOVE" && n.hint != "BELOW") return 0.0
        return when {
            n.rect.right <= content.x -> content.x - n.rect.x
            n.rect.x >= content.right -> n.rect.right - content.right
            else -> 0.0
        }
    }

    fun leaderOk(
        n: HintNote,
        content: SvgRect,
    ): Boolean {
        val toEdge =
            when (n.hint) {
                "ABOVE" -> max(0.0, n.box.y - content.y)
                "BELOW" -> max(0.0, content.bottom - n.box.bottom)
                else -> return true
            }
        return n.leaderLength <= toEdge + STACK_GAP + LEADER_SLACK + 0.01
    }

    private val noteHead =
        Regex(
            """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<path d="M 0 0 L [-0-9.]+ 0 L ([-0-9.]+) [-0-9.]+ L [-0-9.]+ ([-0-9.]+) L 0 [-0-9.]+ Z" class="kuml-comment[ "]""",
        )

    private fun nodeBox(node: SvgNode): SvgRect? {
        val tag = Regex("""<rect\b[^>]*>""").find(node.body)?.value ?: return null

        fun a(name: String) =
            Regex("""\b$name="([-0-9.]+)"""")
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: 0.0
        val w = a("width")
        val h = a("height")
        if (w <= 0.0 || h <= 0.0) return null
        return SvgRect(node.tx + a("x"), node.ty + a("y"), w, h)
    }

    fun report(
        svg: String,
        script: String,
    ): NoteHintReport {
        val hints = hintsOf(script).filterValues { it == "ABOVE" || it == "BELOW" || it == "LEFT" || it == "RIGHT" }
        val noteRects =
            noteHead
                .findAll(svg)
                .associate { m ->
                    val v = m.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }
        val nodes = SvgGeometry.nodes(svg)
        val boxes = nodes.filter { it.id !in noteRects }.mapNotNull { n -> nodeBox(n)?.let { n.id to it } }.toMap()
        val content = boxes.values.reduceOrNull { a, b -> union(a, b) }
        val links =
            Regex(
                """<g id="note-link:([^":]+)::([^"]+)">\s*(?:<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)"|<path d="([^"]*)")[^>]*/>\s*<circle cx="([-0-9.]+)" cy="([-0-9.]+)"""",
            ).findAll(svg)
                .toList()
        val out = ArrayList<HintNote>()
        for ((id, hint) in hints) {
            val rect = noteRects[id] ?: continue
            var box: SvgRect? = null
            var length = 0.0
            for (m in links.filter { it.groupValues[1] == id }) {
                val v = m.groupValues
                val pts =
                    if (v[7].isNotEmpty()) {
                        SvgGeometry.pathPoints(v[7])
                    } else {
                        listOf(v[3].toDouble() to v[4].toDouble(), v[5].toDouble() to v[6].toDouble())
                    }
                length += pts.zipWithNext().sumOf { (p, q) -> hypot(q.first - p.first, q.second - p.second) }
                val target = v[2]
                val dot = SvgRect(v[8].toDouble(), v[9].toDouble(), 0.0, 0.0)
                val b = boxes[target] ?: dot
                box = box?.let { union(it, b) } ?: b
            }
            if (box == null) continue
            out += HintNote(noteId = id, hint = hint, rect = rect, box = box, leaderLength = length, seq = script.contains("seqDiagram("))
        }
        val width =
            Regex("""<svg[^>]*\bviewBox="[-0-9.]+ [-0-9.]+ ([-0-9.]+) """)
                .find(svg)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: 0.0
        return NoteHintReport(notes = out, content = content, svgWidth = width)
    }

    private fun union(
        a: SvgRect,
        b: SvgRect,
    ): SvgRect {
        val x = min(a.x, b.x)
        val y = min(a.y, b.y)
        return SvgRect(x, y, max(a.right, b.right) - x, max(a.bottom, b.bottom) - y)
    }
}
