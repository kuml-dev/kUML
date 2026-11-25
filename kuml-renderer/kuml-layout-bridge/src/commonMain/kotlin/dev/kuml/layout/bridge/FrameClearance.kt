@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Smallest accepted distance (px) between an edge segment that runs parallel to a composite frame side and that side (PAL wave P). */
internal const val FRAME_STANDOFF_MIN_PX = 12f

/** Distance (px) of the first lane an edge segment is moved to away from a composite frame side (PAL wave P). */
internal const val FRAME_STANDOFF_TARGET_PX = 16f

/** Upper bound of routes / points / groups examined (hostile-input cap). */
private const val MAX_ROUTES = 512
private const val MAX_POINTS = 64
private const val MAX_GROUPS = 256

/** A segment of [edge] that runs parallel to a side of composite [group], closer than [FRAME_STANDOFF_MIN_PX] to it (inside or outside). */
internal data class FrameGapViolation(
    val edge: EdgeId,
    val group: String,
    val gap: Float,
)

/** A horizontal or diagonal stretch of [edge] (length in px) inside the header band of composite [group]. */
internal data class HeaderRunViolation(
    val edge: EdgeId,
    val group: String,
    val length: Float,
)

/** Frame-clearance violations of one state-machine layout (PAL wave P, defect classes F1 and F2). */
internal data class FrameViolations(
    val frameGap: List<FrameGapViolation>,
    val headerRun: List<HeaderRunViolation>,
) {
    /** F1: parallel runs along a frame side closer than 12 px. */
    val f1: Int get() = frameGap.size

    /** F2: horizontal or diagonal runs inside a header band. */
    val f2: Int get() = headerRun.size
}

private const val EPS = 0.5f

/**
 * Finds edge segments that hug a composite frame (F1) or run along its header band (F2). Uses only geometry and the
 * graph's groups, never names. The first and last [ATTACH_EXEMPT_PX] of a route (its own attachment) are exempt from F1;
 * a vertical piercing of the header band is allowed. Hostile-input caps apply.
 */
internal fun detectFrameViolations(
    result: LayoutResult,
    graph: LayoutGraph,
): FrameViolations {
    val gap = ArrayList<FrameGapViolation>()
    val run = ArrayList<HeaderRunViolation>()
    val groups = graph.groups.take(MAX_GROUPS)
    for (e in graph.edges.take(MAX_ROUTES)) {
        val pts = result.edges[e.id]?.let { polylinePoints(it) }?.take(MAX_POINTS) ?: continue
        val free = trimEnds(route = pts, exemptPx = ATTACH_EXEMPT_PX)
        for (g in groups) {
            val frame = result.groups[g.id]?.bounds ?: continue
            var worst: Float? = null
            for ((a, b) in free.zipWithNext()) {
                val d = sideGap(a = a, b = b, frame = frame) ?: continue
                if (d < FRAME_STANDOFF_MIN_PX && (worst == null || d < worst)) worst = d
            }
            if (worst != null) gap += FrameGapViolation(edge = e.id, group = g.id.value, gap = worst)
            if (g.headerTextWidths != null) {
                val header = headerRect(group = g, frame = frame)
                var len = 0f
                for ((a, b) in pts.zipWithNext()) {
                    if (abs(a.x - b.x) < EPS) continue
                    len += insideLength(a = a, b = b, r = header)
                }
                if (len > EPS) run += HeaderRunViolation(edge = e.id, group = g.id.value, length = len)
            }
        }
    }
    return FrameViolations(frameGap = gap, headerRun = run)
}

/**
 * Distance of an axis-parallel segment to the frame side it runs along, `null` when it is not parallel to a side or
 * does not overlap that side by [PARALLEL_MIN_OVERLAP_PX].
 */
private fun sideGap(
    a: Point,
    b: Point,
    frame: Rect,
): Float? {
    val x0 = frame.origin.x
    val y0 = frame.origin.y
    val x1 = x0 + frame.size.width
    val y1 = y0 + frame.size.height
    return when {
        abs(a.y - b.y) < EPS ->
            if (overlapLen(a.x, b.x, x0, x1) >= PARALLEL_MIN_OVERLAP_PX) min(abs(a.y - y0), abs(a.y - y1)) else null
        abs(a.x - b.x) < EPS ->
            if (overlapLen(a.y, b.y, y0, y1) >= PARALLEL_MIN_OVERLAP_PX) min(abs(a.x - x0), abs(a.x - x1)) else null
        else -> null
    }
}

private fun overlapLen(
    a0: Float,
    a1: Float,
    b0: Float,
    b1: Float,
): Float = min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1))

/** Length (px) of the part of segment a-b that lies inside [r] shrunk by 0.5 px (Liang-Barsky). */
private fun insideLength(
    a: Point,
    b: Point,
    r: Rect,
): Float {
    val xMin = r.origin.x + EPS
    val xMax = r.origin.x + r.size.width - EPS
    val yMin = r.origin.y + EPS
    val yMax = r.origin.y + r.size.height - EPS
    if (xMin >= xMax || yMin >= yMax) return 0f
    val dx = b.x - a.x
    val dy = b.y - a.y
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - xMin, xMax - a.x, a.y - yMin, yMax - a.y)
    for (i in 0 until 4) {
        if (p[i] == 0f) {
            if (q[i] < 0f) return 0f
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
                if (t > t1) return 0f
                if (t > t0) t0 = t
            } else {
                if (t < t0) return 0f
                if (t < t1) t1 = t
            }
        }
    }
    return if (t1 > t0) (t1 - t0) * sqrt(dx * dx + dy * dy) else 0f
}

/**
 * Public view of the frame-clearance detector for tests and tools (PAL wave P): runs of edges along composite frames
 * (F1) and along header bands (F2) of a state-machine layout.
 */
public object StmFrameClearance {
    /** F1: number of (edge, composite) pairs whose route runs parallel to a frame side closer than 12 px. */
    public fun frameGapCount(
        result: LayoutResult,
        graph: LayoutGraph,
    ): Int = detectFrameViolations(result = result, graph = graph).f1

    /** F2: number of (edge, composite) pairs whose route has a horizontal or diagonal run inside the header band. */
    public fun headerRunCount(
        result: LayoutResult,
        graph: LayoutGraph,
    ): Int = detectFrameViolations(result = result, graph = graph).f2

    /** F3: number of parallel-edge corridor violations (edges closer than 16 px side by side), as counted by [StmClearance]. */
    public fun parallelCount(
        result: LayoutResult,
        graph: LayoutGraph,
    ): Int = detectViolations(result = result, graph = graph).parallel.size

    /** One readable line per F1/F2 violation. */
    public fun describe(
        result: LayoutResult,
        graph: LayoutGraph,
    ): List<String> {
        val v = detectFrameViolations(result = result, graph = graph)
        return v.frameGap.map { "F1 ${it.edge.value} along ${it.group}: ${it.gap} px" } +
            v.headerRun.map { "F2 ${it.edge.value} in header of ${it.group}: ${it.length} px" }
    }

    /** The route polylines as text, for diagnostics. */
    public fun routes(result: LayoutResult): List<String> =
        result.edges.map { (id, r) -> "${id.value}: " + (polylinePoints(r)?.joinToString(" ") { "(${it.x},${it.y})" } ?: "curved") }

    /** The frames of the groups as text, for diagnostics. */
    public fun frames(result: LayoutResult): List<String> =
        result.groups.map { (id, g) ->
            "${id.value}: ${g.bounds.origin.x},${g.bounds.origin.y} ${g.bounds.size.width}x${g.bounds.size.height}"
        }
}
