@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.max
import kotlin.math.min

/**
 * PAL Welle X7 detector: C4 notes inside the system boundary frame. Measured on the rendered SVG with rectangles and polylines
 * only (never glyph metrics). The numbers [TITLE_INK_PX], [GAP_PX] and [GRID_PX] are the ones the placer uses
 * (`C4NotePlacement.C4_FRAME_TITLE_KEEP_OUT_PX` = 34 + 12, `C4_FRAME_NOTE_GAP_PX`); a small equality test guards them.
 *
 * - C1 a note whose anchors are all members of the frame (the anchor line ends in a node rectangle inside the frame) lies outside
 *   the frame although the grid search (step [GRID_PX]) finds a rectangle of the note size inside the frame (inset by [GAP_PX],
 *   top edge at frame top + [TITLE_INK_PX] + [GAP_PX]) that keeps [GAP_PX] to every node, edge polyline, edge label, other note
 *   and the title strip, and from which every anchor dot is reachable by a straight line that crosses no foreign node.
 * - C2 a note rectangle overlaps the title strip T (frame top, [TITLE_INK_PX] high), crosses a frame line, or lies inside the
 *   frame closer than [GAP_PX] to a frame line.
 * - C3 the viewBox is wider than the non-note shapes plus 2 * 24 px, and the extra width is caused by a C1 note (the width is
 *   measured again without the C1 notes). Without a C1 note the extra width is only informative ([Report.extraPx], [Report.ratio]).
 */
internal object C4FrameNoteMetrics {
    const val TITLE_INK_PX = 34f
    const val GAP_PX = 12f
    const val GRID_PX = 4f
    private const val MARGIN_PX = 24.0
    private const val NOTE_END_TOL = 3.0
    private const val DOT_TOL = 2.0

    data class Frame(
        val id: String,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    ) {
        val rect: SvgRect get() = SvgRect(x, y, w, h)
    }

    data class Report(
        val c1: Int,
        val c2: Int,
        val c3: Int,
        val extraPx: Double,
        val ratio: Double,
        val offenders: List<String>,
    ) {
        override fun toString(): String =
            "C1=$c1 C2=$c2 C3=$c3 extraPx=${"%.1f".format(
                java.util.Locale.ROOT,
                extraPx,
            )} ratio=${"%.3f".format(java.util.Locale.ROOT, ratio)}"
    }

    private val frameRe =
        Regex(
            """<g id="(system-[^"]+)" transform="translate\(([-0-9.]+),\s*([-0-9.]+)\)">\s*<rect[^>]*?width="([-0-9.]+)" height="([-0-9.]+)"""",
        )
    private val edgeTagRe = Regex("""<(path|line)\b[^>]*data-edge-id="[^"]*"[^>]*/>""")

    /** The system boundary frames (`system-<id>` groups) with absolute coordinates. */
    fun frames(svg: String): List<Frame> =
        frameRe
            .findAll(svg)
            .map {
                val v = it.groupValues
                Frame(v[1], v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }.toList()

    /** Polylines of all C4 edges (`<path|line ... class="kuml-edge" data-edge-id=...>` directly in the edge layer). */
    fun edges(svg: String): List<List<Pair<Double, Double>>> {
        val out = ArrayList<List<Pair<Double, Double>>>()
        for (m in edgeTagRe.findAll(svg)) {
            val tag = m.value
            if (!tag.contains("class=\"kuml-edge\"")) continue
            if (m.groupValues[1] == "path") {
                val d = Regex("""\sd="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue
                out += SvgGeometry.pathPoints(d)
            } else {
                val v =
                    listOf("x1", "y1", "x2", "y2").map { n ->
                        Regex("""\b$n="([-0-9.]+)"""")
                            .find(tag)
                            ?.groupValues
                            ?.get(1)
                            ?.toDouble()
                    }
                if (v.any { it == null }) continue
                out += listOf(v[0]!! to v[1]!!, v[2]!! to v[3]!!)
            }
        }
        return out
    }

    private val anchorRe =
        Regex("""<(line|path)\b([^>]*?)class="kuml-note-anchor"[^>]*/>\s*<circle cx="([-0-9.]+)" cy="([-0-9.]+)"""")

    /**
     * Anchor end points (the dot of each anchor line) per note id. The C4 renderer writes no ids on anchor lines, so a line belongs
     * to the note whose rectangle its other end touches (within [NOTE_END_TOL] px).
     */
    fun anchorDots(
        svg: String,
        notes: Map<String, SvgRect>,
    ): Map<String, List<Pair<Double, Double>>> {
        val out = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
        for (m in anchorRe.findAll(svg)) {
            val tag = m.value
            val pts =
                if (m.groupValues[1] == "path") {
                    SvgGeometry.pathPoints(Regex("""\sd="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue)
                } else {
                    val v =
                        listOf("x1", "y1", "x2", "y2").map { n ->
                            Regex("""\b$n="([-0-9.]+)"""")
                                .find(tag)
                                ?.groupValues
                                ?.get(1)
                                ?.toDouble()
                        }
                    if (v.any { it == null }) continue
                    listOf(v[0]!! to v[1]!!, v[2]!! to v[3]!!)
                }
            if (pts.size < 2) continue
            val dot = m.groupValues[3].toDouble() to m.groupValues[4].toDouble()

            fun d(p: Pair<Double, Double>) = Math.hypot(p.first - dot.first, p.second - dot.second)
            val noteEnd = if (d(pts.first()) <= d(pts.last())) pts.last() else pts.first()
            val owner =
                notes.entries
                    .map { (id, r) -> id to rectDist(noteEnd, r) }
                    .filter { it.second <= NOTE_END_TOL }
                    .minByOrNull { it.second }
                    ?.first ?: continue
            out.getOrPut(owner) { ArrayList() } += dot
        }
        return out
    }

    private fun rectDist(
        p: Pair<Double, Double>,
        r: SvgRect,
    ): Double = Math.hypot(max(0.0, max(r.x - p.first, p.first - r.right)), max(0.0, max(r.y - p.second, p.second - r.bottom)))

    /** N1 for C4 (as in wave X6): number of (note, drawn edge) pairs where the edge polyline enters the note rectangle. */
    fun edgeHits(svg: String): Int = edgeHitNotes(svg).values.sum()

    /** The notes that edges run through, with the number of edges. */
    fun edgeHitNotes(svg: String): Map<String, Int> {
        val edges = edges(svg)
        return NoteLeaderMetrics
            .noteRects(svg)
            .mapValues { (_, n) -> edges.count { NoteLeaderMetrics.crossesRect(it, n, 1.0) } }
            .filterValues { it > 0 }
    }

    private fun union(rects: Collection<SvgRect>): SvgRect? {
        if (rects.isEmpty()) return null
        val x0 = rects.minOf { it.x }
        val y0 = rects.minOf { it.y }
        return SvgRect(x0, y0, rects.maxOf { it.right } - x0, rects.maxOf { it.bottom } - y0)
    }

    private fun inflated(
        r: SvgRect,
        d: Double,
    ) = SvgRect(r.x - d, r.y - d, r.w + 2 * d, r.h + 2 * d)

    fun measure(svg: String): Report {
        val frames = frames(svg)
        val noteRects = NoteLeaderMetrics.noteRects(svg)
        val frameIds = frames.map { it.id }.toSet()
        val nodes = NoteLeaderMetrics.nodeRects(svg).filterKeys { it !in frameIds && it != "kuml-diagram-frame" }
        val labels = NoteLeaderMetrics.labelRects(svg).map { it.second }
        val edges = edges(svg)
        val dots = anchorDots(svg, noteRects)
        val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg)?.groupValues
        val vbW = vb?.get(1)?.toDouble() ?: 0.0
        var c1 = 0
        var c2 = 0
        val offenders = ArrayList<String>()
        val c1Notes = HashSet<String>()
        val gap = GAP_PX.toDouble()
        for ((id, rect) in noteRects) {
            for (f in frames) {
                val fr = f.rect
                val title = SvgRect(f.x, f.y, f.w, TITLE_INK_PX.toDouble())
                val inside = fr.contains(rect, 0.0)
                val touches = fr.overlaps(rect)
                // C2
                if (touches && (title.overlaps(rect) || !fr.contains(rect, gap))) {
                    c2++
                    offenders += "C2:$id@${f.id}"
                }
                // C1
                val anchors = dots[id].orEmpty()
                if (anchors.isEmpty() || inside || touches) continue
                // member: the anchor dot sits on a node rectangle that lies inside the frame
                val members =
                    anchors.all { (px, py) ->
                        nodes.values.any { r ->
                            fr.contains(r, 0.0) &&
                                r.x - DOT_TOL <= px &&
                                px <= r.right + DOT_TOL &&
                                r.y - DOT_TOL <= py &&
                                py <= r.bottom + DOT_TOL
                        }
                    }
                if (!members) continue
                if (hasSlot(f, rect, id, nodes.values, labels, edges, noteRects, anchors)) {
                    c1++
                    c1Notes += id
                    offenders += "C1:$id@${f.id}"
                }
            }
        }
        val shapes = nodes.values + frames.map { it.rect }
        val base = union(shapes)
        val all = union(shapes + noteRects.values)
        val without = union(shapes + noteRects.filterKeys { it !in c1Notes }.values)
        val contentW = base?.w ?: 0.0
        val extra = max(0.0, vbW - (contentW + 2 * MARGIN_PX))
        val ratio = if (contentW > 0.0) vbW / contentW else 0.0
        val c3 = if (extra > 0.5 && c1Notes.isNotEmpty() && all != null && without != null && all.w > without.w + 0.5) 1 else 0
        if (c3 > 0) offenders += "C3"
        return Report(c1, c2, c3, extra, ratio, offenders)
    }

    /**
     * An anchor line from the slot [c] to [dot] is possible: the straight line from the point of [c] closest to the dot crosses
     * no node rectangle (the node the dot sits on is exempt). The placer routes leaders with at most two bends around nodes;
     * a slot that no straight line can reach would not be routable either.
     */
    private fun visible(
        c: SvgRect,
        dot: Pair<Double, Double>,
        nodes: Collection<SvgRect>,
    ): Boolean {
        val from = max(c.x, min(dot.first, c.right)) to max(c.y, min(dot.second, c.bottom))
        val line = listOf(from, dot)
        return nodes.none { r ->
            val own =
                r.x - DOT_TOL <= dot.first &&
                    dot.first <= r.right + DOT_TOL &&
                    r.y - DOT_TOL <= dot.second &&
                    dot.second <= r.bottom + DOT_TOL
            !own && NoteLeaderMetrics.crossesRect(line, r, 1.0)
        }
    }

    private fun hasSlot(
        f: Frame,
        note: SvgRect,
        noteId: String,
        nodes: Collection<SvgRect>,
        labels: List<SvgRect>,
        edges: List<List<Pair<Double, Double>>>,
        notes: Map<String, SvgRect>,
        dots: List<Pair<Double, Double>>,
    ): Boolean {
        val gap = GAP_PX.toDouble()
        val grid = GRID_PX.toDouble()
        val x0 = f.x + gap
        val x1 = f.x + f.w - gap - note.w
        val y0 = f.y + TITLE_INK_PX + gap
        val y1 = f.y + f.h - gap - note.h
        if (x1 < x0 || y1 < y0) return false
        val others = notes.filterKeys { it != noteId }.values
        // keep-out rectangles (already inflated by the gap), tested with strict interior overlap
        val blocked = (nodes + labels + others).map { inflated(it, gap) }
        var y = y0
        while (y <= y1 + 0.001) {
            var x = x0
            while (x <= x1 + 0.001) {
                val c = SvgRect(x, y, note.w, note.h)
                if (blocked.none { it.overlaps(c) } &&
                    edges.none { NoteLeaderMetrics.crossesRect(it, c, -gap) } &&
                    dots.all { visible(c, it, nodes) }
                ) {
                    return true
                }
                x += grid
            }
            y += grid
        }
        return false
    }
}
