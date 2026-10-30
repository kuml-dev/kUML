package dev.kuml.layout.bridge.note

import dev.kuml.layout.Point
import dev.kuml.layout.Rect

/** What an obstacle in the note placement model is. */
public enum class ObstacleKind {
    /** A diagram node (box, state, lifeline head). */
    NODE,

    /** The title strip of a composite (state, IBD frame, partition): title and entry text live here. */
    COMPOSITE_TITLE,

    /** The estimated rectangle of an edge label. */
    EDGE_LABEL,

    /** The estimated rectangle of a port label. */
    PORT_LABEL,

    /** The stem of a lifeline (polyline). */
    LIFELINE,

    /** A combined fragment frame (polyline). */
    FRAGMENT,

    /** An already placed note. */
    NOTE,

    /** The route of an already placed anchor line (polyline). */
    ANCHOR_ROUTE,

    /** A diagram edge (polyline). */
    EDGE,
}

/** Penalties of the placement cost model; all values are configuration, not code. */
public data class NoteCostModel(
    val nodeRect: Float = 50_000f,
    val labelRect: Float = 50_000f,
    val noteRect: Float = 50_000f,
    val titleRect: Float = 20_000f,
    val lineThroughRect: Float = 2_000f,
    val nodeRoute: Float = 20_000f,
    val labelRoute: Float = 10_000f,
    val noteRoute: Float = 8_000f,
    val titleRoute: Float = 8_000f,
    val lineRoute: Float = 2_000f,
    val anchorRouteCrossing: Float = 500f,
    val anchorRouteOverlap: Float = 3_000f,
    val bend: Float = 6f,
)

/**
 * Rectangles and polylines the placer must avoid. Label rectangles are inflated by the label halo
 * (2 px) plus 2 px, note rectangles by 8 px, so a note never touches a label halo and two notes keep a gap.
 */
public class ObstacleIndex {
    internal class RectObstacle(
        val kind: ObstacleKind,
        val rect: Rect,
        val ownerId: String?,
    )

    internal class LineObstacle(
        val kind: ObstacleKind,
        val points: List<Point>,
        val ownerId: String?,
    )

    internal val rects: MutableList<RectObstacle> = ArrayList()
    internal val lines: MutableList<LineObstacle> = ArrayList()

    /** Adds a rectangle obstacle. [ownerId] exempts it for routes that end at that owner. */
    public fun addRect(
        kind: ObstacleKind,
        r: Rect,
        ownerId: String? = null,
    ) {
        val inflated =
            when (kind) {
                ObstacleKind.EDGE_LABEL, ObstacleKind.PORT_LABEL -> r.inflated(LABEL_INFLATE)
                ObstacleKind.NOTE -> r.inflated(NOTE_INFLATE)
                else -> r
            }
        rects += RectObstacle(kind = kind, rect = inflated, ownerId = ownerId)
    }

    /** Adds a polyline obstacle (edge, lifeline stem, fragment frame, anchor route). */
    public fun addPolyline(
        kind: ObstacleKind,
        pts: List<Point>,
        ownerId: String? = null,
    ) {
        if (pts.size >= 2) lines += LineObstacle(kind = kind, points = pts, ownerId = ownerId)
    }

    /** True if [p] lies inside a label rectangle (including its halo). */
    internal fun insideLabel(p: Point): Boolean =
        rects.any { (it.kind == ObstacleKind.EDGE_LABEL || it.kind == ObstacleKind.PORT_LABEL) && it.rect.contains(p) }

    /** Number of registered obstacles (rectangles and polylines). */
    public val size: Int get() = rects.size + lines.size

    /** Cost of a note rectangle [r]; [exempt] are anchor owners whose own box may be touched by the line but not covered by the note. */
    internal fun rectCost(
        r: Rect,
        costs: NoteCostModel,
        budget: Budget,
    ): Float {
        var cost = 0f
        for (o in rects) {
            budget.spend()
            if (!o.rect.intersects(r)) continue
            cost +=
                when (o.kind) {
                    ObstacleKind.NODE -> costs.nodeRect
                    ObstacleKind.EDGE_LABEL, ObstacleKind.PORT_LABEL -> costs.labelRect
                    ObstacleKind.NOTE -> costs.noteRect
                    ObstacleKind.COMPOSITE_TITLE -> costs.titleRect
                    else -> costs.lineThroughRect
                }
        }
        val clear = r.inflated(LINE_CLEARANCE)
        for (l in lines) {
            budget.spend()
            var hit = false
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = clear)) {
                    hit = true
                    break
                }
            }
            if (hit) {
                cost +=
                    when (l.kind) {
                        ObstacleKind.ANCHOR_ROUTE -> costs.anchorRouteCrossing
                        // A note on top of a lifeline stem hides it: as bad as covering a node.
                        ObstacleKind.LIFELINE -> costs.nodeRect
                        else -> costs.lineThroughRect
                    }
            }
        }
        return cost
    }

    /** Cost of the straight segment [a]-[b]; obstacles owned by [exemptOwners] are ignored. */
    internal fun segmentCost(
        a: Point,
        b: Point,
        exemptOwners: Set<String>,
        costs: NoteCostModel,
        budget: Budget,
    ): Float {
        var cost = 0f
        for (o in rects) {
            budget.spend()
            if (o.ownerId != null && o.ownerId in exemptOwners) continue
            // Boxes count only with their interior: touching the border at the anchor end is fine.
            val hitRect = if (o.kind == ObstacleKind.NODE) o.rect.inflated(-1.5f) else o.rect
            if (!segmentIntersectsRect(a = a, b = b, r = hitRect)) continue
            cost +=
                when (o.kind) {
                    ObstacleKind.NODE -> costs.nodeRoute
                    ObstacleKind.EDGE_LABEL, ObstacleKind.PORT_LABEL -> costs.labelRoute
                    ObstacleKind.NOTE -> costs.noteRoute
                    ObstacleKind.COMPOSITE_TITLE -> costs.titleRoute
                    else -> costs.lineRoute
                }
        }
        for (l in lines) {
            budget.spend()
            if (l.ownerId != null && l.ownerId in exemptOwners) continue
            var hits = 0
            var overlap = 0f
            for (i in 0 until l.points.size - 1) {
                val c = l.points[i]
                val d = l.points[i + 1]
                if (segmentsCross(a = a, b = b, c = c, d = d)) hits++
                overlap += collinearOverlap(a = a, b = b, c = c, d = d)
            }
            if (l.kind == ObstacleKind.ANCHOR_ROUTE) {
                cost += hits * costs.anchorRouteCrossing
                if (overlap > OVERLAP_TOLERANCE) cost += costs.anchorRouteOverlap
            } else {
                cost += hits * costs.lineRoute
            }
        }
        return cost
    }

    /** Rectangles that block routes (nodes, composite titles, labels, notes), nearest first to the segment a-b. */
    internal fun blockingRects(
        a: Point,
        b: Point,
        exemptOwners: Set<String>,
        limit: Int,
    ): List<Rect> {
        val mid = Point(x = (a.x + b.x) / 2f, y = (a.y + b.y) / 2f)
        return rects
            .filter {
                (it.ownerId == null || it.ownerId !in exemptOwners) &&
                    (
                        it.kind == ObstacleKind.NODE ||
                            it.kind == ObstacleKind.COMPOSITE_TITLE ||
                            it.kind == ObstacleKind.EDGE_LABEL ||
                            it.kind == ObstacleKind.PORT_LABEL ||
                            it.kind == ObstacleKind.NOTE
                    ) &&
                    segmentIntersectsRect(a = a, b = b, r = it.rect)
            }.sortedBy { distance(a = mid, b = Point(x = it.rect.cx, y = it.rect.cy)) }
            .take(limit)
            .map { it.rect }
    }

    /** Deterministic work counter that bounds the placement effort (no O(n^2) blow-up on hostile input). */
    internal class Budget(
        private var remaining: Long,
    ) {
        fun spend() {
            if (remaining > 0) remaining--
        }

        val exhausted: Boolean get() = remaining <= 0

        fun refill(n: Long) {
            remaining = n
        }
    }

    private companion object {
        const val LABEL_INFLATE: Float = 4f
        const val NOTE_INFLATE: Float = 8f
        const val LINE_CLEARANCE: Float = 6f
        const val OVERLAP_TOLERANCE: Float = 8f
    }
}
