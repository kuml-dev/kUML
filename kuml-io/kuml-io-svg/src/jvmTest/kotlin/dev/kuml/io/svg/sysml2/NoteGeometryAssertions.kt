@file:Suppress("RequireNamedArguments") // geometry helpers with rectangle literals

package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Nacharbeit 38: the shared geometric checker for placed notes. Everything is plain rectangles and polylines in final
 * layout coordinates, so the checks do not depend on fonts.
 */
internal object NoteGeometryAssertions {
    const val NOTE_CLEARANCE = 16f
    const val ANCHOR_CLEARANCE = 6f

    /** What the notes must keep away from; every map is keyed by an id that only appears in the failure text. */
    class Scene(
        val notes: Map<String, Rect>,
        val nodes: Map<String, Rect> = emptyMap(),
        val ports: Map<String, Rect> = emptyMap(),
        val labels: Map<String, Rect> = emptyMap(),
        val titles: Map<String, Rect> = emptyMap(),
        val edges: Map<String, List<Point>> = emptyMap(),
        /** Anchor lines by link id. */
        val anchorLines: Map<String, AnchorLine> = emptyMap(),
        val frames: Map<String, Rect> = emptyMap(),
        val truncated: Collection<String> = emptyList(),
    )

    /** One anchor line: the note it belongs to, the node ids it may touch (its own targets) and its polyline. */
    class AnchorLine(
        val noteId: String,
        val ownTargets: Set<String>,
        val pts: List<Point>,
    )

    fun inflate(
        r: Rect,
        d: Float,
    ): Rect =
        Rect(
            origin = Point(x = r.origin.x - d, y = r.origin.y - d),
            size = Size(width = r.size.width + 2 * d, height = r.size.height + 2 * d),
        )

    fun hit(
        a: Rect,
        b: Rect,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

    fun segmentHitsRect(
        a: Point,
        b: Point,
        r: Rect,
    ): Boolean {
        val x0 = r.origin.x
        val x1 = r.origin.x + r.size.width
        val y0 = r.origin.y
        val y1 = r.origin.y + r.size.height
        var t0 = 0f
        var t1 = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - x0, x1 - a.x, a.y - y0, y1 - a.y)
        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return true
    }

    fun describe(r: Rect): String = "(${r.origin.x},${r.origin.y} ${r.size.width}x${r.size.height})"

    private fun polylineHits(
        pts: List<Point>,
        zone: Rect,
    ): Boolean = (0 until pts.size - 1).any { segmentHitsRect(pts[it], pts[it + 1], zone) }

    /** All violations of the four rules; an empty list means the scene is clean. */
    fun failures(scene: Scene): List<String> {
        val out = mutableListOf<String>()
        for ((nid, nr) in scene.notes) {
            val zone = inflate(nr, NOTE_CLEARANCE)
            // 1: the note with 16 px clearance cuts no edge, node, port, label, title or other note.
            for ((id, pts) in scene.edges) if (polylineHits(pts, zone)) out += "note $nid ${describe(nr)} within 16 px of edge $id"
            for ((id, r) in scene.nodes) if (hit(zone, r)) out += "note $nid ${describe(nr)} within 16 px of node $id"
            for ((id, r) in scene.ports) if (hit(zone, r)) out += "note $nid ${describe(nr)} within 16 px of port $id"
            for ((id, r) in scene.labels) if (hit(zone, r)) out += "note $nid ${describe(nr)} within 16 px of label $id"
            for ((id, r) in scene.titles) if (hit(zone, r)) out += "note $nid ${describe(nr)} within 16 px of title $id"
            for ((oid, orr) in scene.notes) if (oid != nid && hit(zone, orr)) out += "note $nid within 16 px of note $oid"
            // 3: fully inside or fully outside each frame.
            for ((fid, fr) in scene.frames) {
                val inside =
                    nr.origin.x >= fr.origin.x &&
                        nr.origin.y >= fr.origin.y &&
                        nr.origin.x + nr.size.width <= fr.origin.x + fr.size.width &&
                        nr.origin.y + nr.size.height <= fr.origin.y + fr.size.height
                if (!inside && hit(nr, fr)) out += "note $nid ${describe(nr)} straddles the border of frame $fid"
            }
        }
        // 2: an anchor line keeps 6 px from every foreign note, node and port.
        for ((lid, line) in scene.anchorLines) {
            val noteId = line.noteId
            val pts = line.pts
            for ((oid, orr) in scene.notes) {
                if (oid != noteId && polylineHits(pts, inflate(orr, ANCHOR_CLEARANCE))) out += "anchor line $lid crosses note $oid"
            }
            for ((id, r) in scene.nodes) {
                // The own targets are touched on purpose: only a cut through the inside of a foreign node counts.
                if (id !in line.ownTargets && polylineHits(pts, inflate(r, -2f))) out += "anchor line $lid cuts node $id"
            }
        }
        // 4: nothing truncated.
        for (t in scene.truncated) out += "note $t is reported as truncated"
        return out
    }

    /** True if [r] lies on the preferred side across the flow of its anchor box ([horizontalFlow] = flow runs left to right). */
    fun acrossFlow(
        r: Rect,
        anchor: Rect,
        horizontalFlow: Boolean,
    ): Boolean {
        val sepX = maxOf(0f, r.origin.x - (anchor.origin.x + anchor.size.width), anchor.origin.x - (r.origin.x + r.size.width))
        val sepY = maxOf(0f, r.origin.y - (anchor.origin.y + anchor.size.height), anchor.origin.y - (r.origin.y + r.size.height))
        return if (horizontalFlow) sepY >= sepX else sepX >= sepY
    }
}
