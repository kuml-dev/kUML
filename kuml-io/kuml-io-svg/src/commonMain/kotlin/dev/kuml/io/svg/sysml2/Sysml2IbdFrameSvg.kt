package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage

/**
 * Draws the IBD owner frame (`kuml-ibd-frame`) with the title tab `ibd [part] Owner`. The tab is a pentagon
 * (bevelled lower-right corner) inside the top-left corner of the frame; the layout keeps content out of that zone.
 */
internal fun renderIbdFrame(
    builder: SvgBuilder,
    group: GroupLayout?,
    ownerId: String,
    ownerName: String,
    padding: Float,
) {
    if (group == null) return
    val x = group.bounds.origin.x + padding
    val y = group.bounds.origin.y + padding
    val w = group.bounds.size.width
    val h = group.bounds.size.height
    val title = Sysml2LayoutBridge.ibdTabTitle(ownerName)
    val tabW = Sysml2LayoutBridge.ibdTabWidth(ownerName)
    val tabH = Sysml2LayoutBridge.IBD_FRAME_TAB_H_PX
    val bevel = 8f
    builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr("frame:$ownerId"))) {
        tag(
            name = "rect",
            attrs = mapOf("x" to fmt3(x), "y" to fmt3(y), "width" to fmt3(w), "height" to fmt3(h), "class" to "kuml-ibd-frame"),
        )
        val points =
            listOf(
                x to y,
                x + tabW to y,
                x + tabW to y + tabH - bevel,
                x + tabW - bevel to y + tabH,
                x to y + tabH,
            ).joinToString(" ") { "${fmt3(it.first)},${fmt3(it.second)}" }
        tag(name = "polygon", attrs = mapOf("points" to points, "class" to "kuml-ibd-frame-tab"))
        tag(
            name = "text",
            attrs = mapOf("class" to "kuml-body", "x" to fmt3(x + 8f), "y" to fmt3(y + tabH - 9f), "text-anchor" to "start"),
        ) { text(title) }
    }
}

/** Draws the owner's boundary ports on the frame: square, direction triangle, `name : Type` label outside the frame. */
internal fun renderIbdOwnerPorts(
    builder: SvgBuilder,
    layout: LayoutResult,
    ports: List<PortUsage>,
    context: Sysml2NodeContext,
    ownerId: String,
    padding: Float,
) {
    val frame = layout.groups[Sysml2LayoutBridge.ibdFrameGroupId(ownerId)] ?: return
    val fx0 = frame.bounds.origin.x
    val fy0 = frame.bounds.origin.y
    val fx1 = fx0 + frame.bounds.size.width
    val fy1 = fy0 + frame.bounds.size.height
    val half = IBD_PORT_SIZE / 2f
    for (port in ports) {
        val b = layout.nodes[NodeId(port.id)]?.bounds ?: continue
        val cx = b.origin.x + b.size.width / 2f
        val cy = b.origin.y + b.size.height / 2f
        // Smallest distance wins (no threshold) — robust for ports near a corner.
        val side =
            listOf(
                Sysml2LayoutBridge.IbdSide.Top to kotlin.math.abs(cy - fy0),
                Sysml2LayoutBridge.IbdSide.Bottom to kotlin.math.abs(cy - fy1),
                Sysml2LayoutBridge.IbdSide.Left to kotlin.math.abs(cx - fx0),
                Sysml2LayoutBridge.IbdSide.Right to kotlin.math.abs(cx - fx1),
            ).minBy { it.second }.first
        val (ix, iy) =
            when (side) {
                Sysml2LayoutBridge.IbdSide.Top -> 0f to 1f
                Sysml2LayoutBridge.IbdSide.Bottom -> 0f to -1f
                Sysml2LayoutBridge.IbdSide.Left -> 1f to 0f
                Sysml2LayoutBridge.IbdSide.Right -> -1f to 0f
            }
        val label = portLabel(name = port.name, port = port, context = context)
        val placement = Sysml2LayoutBridge.ibdLabelPlacement(side = side, cx = cx, cy = cy, text = label, partPort = false)
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(port.id))) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(cx - half + padding),
                        "y" to fmt3(cy - half + padding),
                        "width" to fmt3(IBD_PORT_SIZE),
                        "height" to fmt3(IBD_PORT_SIZE),
                        "class" to "kuml-port kuml-sysml2-port",
                    ),
            )
            if (port.direction != null && port.direction != PortDirection.InOut) {
                val (dx, dy) = if (port.direction == PortDirection.In) ix to iy else -ix to -iy
                appendDirectionTriangle(cx = cx + padding, cy = cy + padding, dx = dx, dy = dy)
            }
            appendPortLabel(placement = placement, text = label, offsetX = padding, offsetY = padding)
        }
    }
}

/**
 * Label anchor for an IBD connector: the longest straight stretch of the route, shifted beside the line (above a
 * horizontal run, right of a vertical run) and moved along the route until the label rectangle clears every
 * [avoid] rectangle (port squares with clearance, port labels, part boxes). [avoid] is given in layout
 * coordinates; [route] is already shifted by [padding].
 *
 * The anchor semantics are those of `Sysml2EdgeRenderer`: the label rectangle spans
 * `[mx - halfWidth, mx + halfWidth] x [my - 14, my - 2]`.
 */
internal fun ibdConnectorLabelAnchor(
    route: dev.kuml.layout.EdgeRoute,
    labelText: String?,
    avoid: List<dev.kuml.layout.Rect>,
    padding: Float,
): Pair<Float, Float>? {
    if (labelText.isNullOrBlank()) return null
    val hw =
        dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
            .estimateLabelHalfWidth(labelText)
    val pts =
        when (route) {
            is dev.kuml.layout.EdgeRoute.Direct -> listOf(route.source, route.target)
            is dev.kuml.layout.EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is dev.kuml.layout.EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is dev.kuml.layout.EdgeRoute.Bezier -> listOf(route.source, route.target)
        }

    fun len2(i: Int): Float {
        val dx = pts[i + 1].x - pts[i].x
        val dy = pts[i + 1].y - pts[i].y
        return dx * dx + dy * dy
    }
    val segs = (0 until pts.size - 1).filter { len2(it) > 0.01f }.sortedByDescending { len2(it) }
    // Candidate = (anchor, index of the segment it belongs to). Horizontal runs: label above, then below;
    // vertical runs: label right, then left. The label may never touch another segment of its own route.
    val candidates = mutableListOf<Pair<Pair<Float, Float>, Int>>()
    for (i in segs) {
        val a = pts[i]
        val b = pts[i + 1]
        val vertical = kotlin.math.abs(b.y - a.y) > kotlin.math.abs(b.x - a.x)
        for (t in listOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f)) {
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            if (vertical) {
                candidates += ((px + hw + 6f) to (py + 8f)) to i
                candidates += ((px - hw - 6f) to (py + 8f)) to i
            } else {
                candidates += (px to (py - 4f)) to i
                candidates += (px to (py + 20f)) to i
            }
        }
    }
    if (candidates.isEmpty()) return null

    fun clear(c: Pair<Pair<Float, Float>, Int>): Boolean {
        val l = c.first.first - hw
        val r = c.first.first + hw
        val t = c.first.second - 14f
        val bt = c.first.second - 2f
        val free =
            avoid.none { a ->
                val al = a.origin.x + padding
                val at = a.origin.y + padding
                l < al + a.size.width && al < r && t < at + a.size.height && at < bt
            }
        if (!free) return false
        return (0 until pts.size - 1).none { j ->
            j != c.second &&
                l < maxOf(pts[j].x, pts[j + 1].x) + 1f &&
                minOf(pts[j].x, pts[j + 1].x) - 1f < r &&
                t < maxOf(pts[j].y, pts[j + 1].y) + 1f &&
                minOf(pts[j].y, pts[j + 1].y) - 1f < bt
        }
    }
    return (candidates.firstOrNull(::clear) ?: candidates.first()).first
}
