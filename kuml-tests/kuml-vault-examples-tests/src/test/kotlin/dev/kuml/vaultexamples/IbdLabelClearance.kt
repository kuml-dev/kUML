package dev.kuml.vaultexamples

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.label.LabelRole
import dev.kuml.layout.bridge.label.PortLabelAudit
import dev.kuml.layout.bridge.note.ObstacleKind

/**
 * PAL Welle U detector: measures how much room the finished label plan of an IBD leaves around its labels. It works on the
 * plan rectangles of one [PortLabelAudit.Snapshot] (never on SVG glyphs), so the result does not depend on fonts or on the
 * platform.
 *
 * Definitions (eps = [EPS] px, gap threshold = [MIN_GAP] px):
 * - **foreign part**: a part box (node, nested frame or the title strip inside them) that is neither an owner of the label
 *   nor an ancestor of an owner. Owners of a port label: the part the port belongs to. Owners of a connector label: the
 *   parts of both connector end points. Ancestors are the `::` path prefixes.
 * - C2: a connector or item flow label (every role except PORT) overlaps a foreign part (by more than eps in both axes).
 * - C3: two labels (all roles) overlap each other (by more than eps in both axes), counted once per pair.
 * - C4: a label is hit by a segment of a foreign connector (not its own edge).
 * - C5: the gap between a label and a foreign part is below [MIN_GAP] (touching is gap 0), without being a C2 overlap.
 *
 * C2 to C5 are counted per label and foreign part (C3 per pair of labels, C4 per label and edge). Port labels take part in
 * C3 to C5 with their own part as owner.
 */
object IbdLabelClearance {
    const val EPS: Float = 0.5f
    const val MIN_GAP: Float = 6.0f

    data class Counts(
        val c2: Int,
        val c3: Int,
        val c4: Int,
        val c5: Int,
        val details: List<String>,
    ) {
        val sum: Int get() = c2 + c3 + c4 + c5
    }

    private class Part(
        val path: String,
        val rect: Rect,
    )

    fun measure(snapshot: PortLabelAudit.Snapshot): Counts {
        val parts = partsOf(snapshot)
        val edges = snapshot.lines.filter { it.kind == ObstacleKind.EDGE && it.ownerId?.startsWith("lane:") != true }
        val details = ArrayList<String>()
        var c2 = 0
        var c3 = 0
        var c4 = 0
        var c5 = 0
        val labels = snapshot.labels
        for (label in labels) {
            val owners = ownersOf(label = label, snapshot = snapshot)
            val exempt = owners.flatMap { ancestorsAndSelf(it) }.toSet()
            val overlapped = HashSet<String>()
            for (p in parts) {
                if (p.path in exempt) continue
                if (overlap(a = label.rect, b = p.rect)) {
                    if (label.role != LabelRole.PORT && overlapped.add(p.path)) {
                        c2++
                        details += "C2 ${label.id} x part ${p.path}"
                    }
                    overlapped.add(p.path)
                }
            }
            for (p in parts.sortedBy { it.path }.distinctBy { it.path }) {
                if (p.path in exempt || p.path in overlapped) continue
                if (gap(a = label.rect, b = p.rect) < MIN_GAP) {
                    c5++
                    details += "C5 ${label.id} near part ${p.path} (gap ${"%.1f".format(gap(a = label.rect, b = p.rect))})"
                }
            }
            val hitEdges = HashSet<String>()
            for (e in edges) {
                val id = e.ownerId ?: continue
                if (label.role != LabelRole.PORT && id == label.ownerId) continue
                if (hits(pts = e.points, rect = label.rect) && hitEdges.add(id)) {
                    c4++
                    details += "C4 ${label.id} x edge $id"
                }
            }
        }
        for (i in labels.indices) {
            for (j in i + 1 until labels.size) {
                if (overlap(a = labels[i].rect, b = labels[j].rect)) {
                    c3++
                    details += "C3 ${labels[i].id} x ${labels[j].id}"
                }
            }
        }
        return Counts(c2 = c2, c3 = c3, c4 = c4, c5 = c5, details = details)
    }

    /** Part boxes of a plan: nodes, nested frames (bounding box of the frame line) and title strips attributed to the smallest enclosing part. */
    private fun partsOf(s: PortLabelAudit.Snapshot): List<Part> {
        val boxes = ArrayList<Part>()
        for (o in s.obstacles) {
            val id = o.ownerId ?: continue
            if (o.kind == ObstacleKind.NODE && !isNote(id = id, s = s)) boxes += Part(path = id.removePrefix("frame:"), rect = o.rect)
        }
        for (l in s.lines) {
            val id = l.ownerId ?: continue
            if (l.kind != ObstacleKind.FRAGMENT || id.startsWith("lane:")) continue
            boxes += Part(path = id.removePrefix("frame:"), rect = bbox(l.points))
        }
        val titles = ArrayList<Part>()
        for (o in s.obstacles) {
            if (o.kind != ObstacleKind.COMPOSITE_TITLE) continue
            val id = o.ownerId
            if (id != null) {
                titles += Part(path = id.removePrefix("frame:"), rect = o.rect)
                continue
            }
            val cx = o.rect.origin.x + o.rect.size.width / 2f
            val cy = o.rect.origin.y + o.rect.size.height / 2f
            val owner = boxes.filter { contains(r = it.rect, x = cx, y = cy) }.minByOrNull { it.rect.size.width * it.rect.size.height }
            if (owner != null) titles += Part(path = owner.path, rect = o.rect)
        }
        return boxes + titles
    }

    private fun ownersOf(
        label: PortLabelAudit.LabelEntry,
        snapshot: PortLabelAudit.Snapshot,
    ): List<String> {
        if (label.role == LabelRole.PORT) return listOfNotNull(ownerOfPort(label.id) ?: label.parentId)
        val line = snapshot.lines.firstOrNull { it.kind == ObstacleKind.EDGE && it.ownerId == label.ownerId } ?: return emptyList()
        val ends = listOf(line.points.first(), line.points.last())
        val out = ArrayList<String>()
        val parts =
            snapshot.obstacles.filter {
                it.kind == ObstacleKind.NODE &&
                    it.ownerId != null &&
                    !isNote(id = it.ownerId!!, s = snapshot)
            }
        for (end in ends) {
            val pin = snapshot.obstacles.firstOrNull { it.kind == ObstacleKind.PORT && gapToPoint(r = it.rect, p = end) <= 1.5f }
            val owner =
                parts
                    .filter {
                        (pin == null && gapToPoint(r = it.rect, p = end) <= 1.5f) ||
                            (pin != null && gap(a = it.rect, b = pin.rect) <= EPS)
                    }.minByOrNull { it.rect.size.width * it.rect.size.height }
                    ?.ownerId
            if (owner != null) out += owner
        }
        return out
    }

    /** A node that a `note-link:<id>::...` anchor line starts at is a note, not a part. */
    private fun isNote(
        id: String,
        s: PortLabelAudit.Snapshot,
    ): Boolean = id.startsWith("canvas:") || s.lines.any { it.ownerId?.startsWith("note-link:$id::") == true }

    /** `port:<part path>::<port name>` gives the part path; other shapes give null. */
    private fun ownerOfPort(id: String): String? {
        val body = id.removePrefix("port:")
        val cut = body.lastIndexOf("::")
        return if (cut > 0) body.substring(0, cut) else null
    }

    private fun ancestorsAndSelf(path: String): List<String> {
        val out = ArrayList<String>()
        var rest = path
        out += rest
        while (rest.contains("::")) {
            rest = rest.substring(0, rest.lastIndexOf("::"))
            out += rest
        }
        return out
    }

    private fun right(r: Rect) = r.origin.x + r.size.width

    private fun bottom(r: Rect) = r.origin.y + r.size.height

    private fun contains(
        r: Rect,
        x: Float,
        y: Float,
    ) = x >= r.origin.x && x <= right(r) && y >= r.origin.y && y <= bottom(r)

    private fun bbox(points: List<Point>): Rect {
        val x0 = points.minOf { it.x }
        val y0 = points.minOf { it.y }
        return Rect(
            origin = Point(x = x0, y = y0),
            size =
                dev.kuml.layout.Size(
                    width = points.maxOf { it.x } - x0,
                    height =
                        points.maxOf { it.y } - y0,
                ),
        )
    }

    fun overlap(
        a: Rect,
        b: Rect,
    ): Boolean =
        minOf(right(a), right(b)) - maxOf(a.origin.x, b.origin.x) > EPS &&
            minOf(bottom(a), bottom(b)) - maxOf(a.origin.y, b.origin.y) > EPS

    /** Rectangle distance, 0 when touching or overlapping. */
    fun gap(
        a: Rect,
        b: Rect,
    ): Float {
        val dx = maxOf(0f, maxOf(a.origin.x, b.origin.x) - minOf(right(a), right(b)))
        val dy = maxOf(0f, maxOf(a.origin.y, b.origin.y) - minOf(bottom(a), bottom(b)))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun gapToPoint(
        r: Rect,
        p: Point,
    ): Float {
        val dx = maxOf(0f, maxOf(r.origin.x - p.x, p.x - right(r)))
        val dy = maxOf(0f, maxOf(r.origin.y - p.y, p.y - bottom(r)))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** True when a segment of [pts] crosses [rect] shrunk by eps (Liang-Barsky). */
    private fun hits(
        pts: List<Point>,
        rect: Rect,
    ): Boolean {
        val l = rect.origin.x + EPS
        val t = rect.origin.y + EPS
        val r = right(rect) - EPS
        val b = bottom(rect) - EPS
        if (r <= l || b <= t) return false
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val c = pts[i + 1]
            var t0 = 0f
            var t1 = 1f
            val dx = c.x - a.x
            val dy = c.y - a.y
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(a.x - l, r - a.x, a.y - t, b - a.y)
            var inside = true
            for (k in 0 until 4) {
                if (p[k] == 0f) {
                    if (q[k] < 0f) {
                        inside = false
                        break
                    }
                } else {
                    val u = q[k] / p[k]
                    if (p[k] < 0f) t0 = maxOf(t0, u) else t1 = minOf(t1, u)
                }
            }
            if (inside && t0 <= t1) return true
        }
        return false
    }
}
