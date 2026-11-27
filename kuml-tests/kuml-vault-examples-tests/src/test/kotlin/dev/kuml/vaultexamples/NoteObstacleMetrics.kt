@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.max

/** Violation counts of one note (or the sum over a diagram), see [NoteObstacleMetrics]. */
internal data class NoteObstacleViolations(
    val n1: Int,
    val n2: Int,
    val n3: Int,
) {
    val sum: Int get() = n1 + n2 + n3

    override fun toString(): String = "N1=$n1 N2=$n2 N3=$n3"
}

internal class NoteObstacleReport(
    val perNote: Map<String, NoteObstacleViolations>,
    val total: NoteObstacleViolations,
)

/**
 * PAL Welle X6 detector: notes as obstacles for edge routing. Measured on the rendered SVG with rectangles and polylines only
 * (never glyph metrics: the width heuristic is characters times the body character width, identical on every machine).
 *
 * - N1 a foreign edge polyline enters the interior (shrunk by [SHRINK] px) of the note rectangle. Exempt: the edge the note is
 *   anchored to (and its `conn:` alias). Anchor lines (`note-link:`) are no edges.
 * - N2 the note rectangle overlaps a foreign node, composite or swimlane rectangle without lying completely inside it. A note
 *   that lies completely inside a container is no violation (the container frame is not crossed).
 * - N3 the note box is smaller than its text needs: width below longest line * 6.6 + text inset + right padding, or height
 *   below lines * 15 + 16 (at least 32), tolerance [TOL] px.
 */
internal object NoteObstacleMetrics {
    private const val SHRINK = 1.0
    private const val TOL = 0.5
    private const val BODY_CHAR_PX = 6.6
    private const val LINE_H = 15.0
    private const val V_PADDING = 16.0
    private const val MIN_H = 32.0
    private const val PAD_RIGHT_DOG_EAR = 22.0
    private const val PAD_RIGHT_BRACKET = 8.0

    private fun shrunk(r: SvgRect) = SvgRect(r.x + SHRINK, r.y + SHRINK, r.w - 2 * SHRINK, r.h - 2 * SHRINK)

    /** Per note: the note id to the text lines and the text inset read from the node group body. */
    private fun noteText(
        svg: String,
        id: String,
    ): Triple<List<String>, Double, Boolean>? {
        val node = SvgGeometry.nodes(svg).firstOrNull { it.id == id } ?: return null
        val body = node.body.substringBefore("</g>")
        val dogEar = body.contains("kuml-comment-fold")
        val tx =
            Regex("""<text[^>]*class="kuml-body"[^>]*\sx="([-0-9.]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: 8.0
        val tspans =
            Regex("""<tspan\b[^>]*\sdy="[^"]*"[^>]*>\s*([^<]*?)\s*</tspan>""").findAll(body).map { it.groupValues[1] }.toList()
        val lines =
            tspans.ifEmpty {
                Regex("""<text[^>]*class="kuml-body"[^>]*>\s*([^<]*?)\s*</text>""").findAll(body).map { it.groupValues[1] }.toList()
            }
        return Triple(lines, tx, dogEar)
    }

    private val groupRe =
        Regex("""<g id="group:[^"]*"[^>]*>\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""")

    /** Requirement group frames (`group:` groups with their own rectangle, absolute coordinates). */
    fun groupRects(svg: String): List<SvgRect> =
        groupRe
            .findAll(svg)
            .map {
                SvgRect(
                    it.groupValues[1].toDouble(),
                    it.groupValues[2].toDouble(),
                    it.groupValues[3].toDouble(),
                    it.groupValues[4].toDouble(),
                )
            }.toList()

    fun n3(
        svg: String,
        noteId: String,
        rect: SvgRect,
    ): Int {
        val (lines, tx, dogEar) = noteText(svg, noteId) ?: return 0
        if (lines.isEmpty()) return 0
        val widest = lines.maxOf { it.length } * BODY_CHAR_PX
        val reqW = tx + widest + if (dogEar) PAD_RIGHT_DOG_EAR else PAD_RIGHT_BRACKET
        val reqH = max(MIN_H, lines.size * LINE_H + V_PADDING)
        var n = 0
        if (rect.w < reqW - TOL) n++
        if (rect.h < reqH - TOL) n++
        return n
    }

    fun report(svg: String): NoteObstacleReport {
        val notes = NoteLeaderMetrics.noteRects(svg)
        val nodes = NoteLeaderMetrics.nodeRects(svg)
        val lanes = NoteLeaderMetrics.laneBoundaries(svg)
        val groups = groupRects(svg)
        val edges = SvgGeometry.edgePolylines(svg)
        val leaders = NoteLeaderMetrics.leaders(svg)
        val per = LinkedHashMap<String, NoteObstacleViolations>()
        for ((id, rect) in notes) {
            val targets = leaders.filter { it.noteId == id }.map { it.targetId }
            var n1 = 0
            for ((eid, pts) in edges) {
                if (targets.any { NoteLeaderMetrics.sameEdge(eid, it) }) continue
                if (NoteLeaderMetrics.crossesRect(pts, rect, SHRINK)) n1++
            }
            var n2 = 0
            val s = shrunk(rect)
            for ((nid, r) in nodes) {
                if (nid == id) continue
                if (r.contains(rect, 0.0)) continue
                if (r.overlaps(s)) n2++
            }
            for (g in groups) {
                if (g.contains(rect, 0.0)) continue
                if (g.overlaps(s)) n2++
            }
            for (lane in lanes) {
                if (lane.contains(rect, 0.0)) continue
                if (lane.overlaps(s)) n2++
            }
            per[id] = NoteObstacleViolations(n1, n2, n3(svg, id, rect))
        }
        val total =
            NoteObstacleViolations(per.values.sumOf { it.n1 }, per.values.sumOf { it.n2 }, per.values.sumOf { it.n3 })
        return NoteObstacleReport(per, total)
    }
}
