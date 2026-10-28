package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.PortSide
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model

/**
 * Single source of truth for the vertical layout of a constraint box (title, expression, parameter rows).
 * The SVG renderer (`renderConstraintDefinition`) draws with exactly these numbers and the bridge derives the pin
 * positions from them, so box text and pin markers cannot drift apart.
 */
public object ParConstraintGeometry {
    /** Baseline of the `«constraint»` stereotype line. */
    public const val STEREOTYPE_BASELINE: Float = 16f

    /** Baseline of the name line. */
    public const val NAME_BASELINE: Float = 30f

    /** y of the first divider (below the name). */
    public const val FIRST_DIVIDER_Y: Float = 42f

    /** Baseline of the expression line (when there is one). */
    public const val EXPRESSION_BASELINE: Float = 56f

    /** y of the divider above the parameter list when there is an expression. */
    public const val DIVIDER_AFTER_EXPRESSION_Y: Float = 69f

    /** Distance between a divider and the first parameter baseline. */
    public const val PARAM_FIRST_BASELINE_OFFSET: Float = 14f

    /** Row height of the parameter list. */
    public const val PARAM_ROW_H: Float = 13f

    /** The pin sits this far above the parameter baseline (roughly the middle of the text line). */
    public const val PIN_BASELINE_OFFSET: Float = 4f

    /** Edge length of the square pin marker. */
    public const val PIN_SIZE: Float = 8f

    /** Left indent of the parameter text; leaves room for the inner routing lanes. */
    public const val PARAM_TEXT_INDENT: Float = 18f

    /** Baseline of parameter row [index]. */
    public fun paramBaseline(
        hasExpression: Boolean,
        index: Int,
    ): Float {
        val divider = if (hasExpression) DIVIDER_AFTER_EXPRESSION_Y else FIRST_DIVIDER_Y
        return divider + PARAM_FIRST_BASELINE_OFFSET + index * PARAM_ROW_H
    }

    /** y of the pin of parameter row [index], relative to the top of the box. */
    public fun pinY(
        hasExpression: Boolean,
        index: Int,
    ): Float = paramBaseline(hasExpression = hasExpression, index = index) - PIN_BASELINE_OFFSET

    /** `in` parameters dock on the left, `out` and `inout` on the right. */
    public fun pinSide(direction: ConstraintParameterDirection): PortSide =
        when (direction) {
            ConstraintParameterDirection.In -> PortSide.West
            ConstraintParameterDirection.Out, ConstraintParameterDirection.Inout -> PortSide.East
        }

    /** Layout port id of a parameter pin: `"<constraintId>::<parameter>"`, the same string a binding endpoint uses. */
    public fun portId(
        constraint: ConstraintDefinition,
        parameter: ConstraintParameter,
    ): PortId = PortId("${constraint.id}::${parameter.name}")

    /** Pin centre in absolute coordinates for a box at [bounds]. */
    public fun pinPoint(
        constraint: ConstraintDefinition,
        index: Int,
        bounds: Rect,
    ): Point {
        val p = constraint.parameters[index]
        val y = bounds.origin.y + pinY(hasExpression = constraint.expression.isNotEmpty(), index = index)
        val x = if (pinSide(p.direction) == PortSide.West) bounds.origin.x else bounds.origin.x + bounds.size.width
        return Point(x = x, y = y)
    }
}

/**
 * Visible constraint hierarchy of a parametric diagram.
 *
 * The owner chain is validated defensively: a constraint whose chain contains a cycle, an unknown or invisible
 * owner, or is deeper than [Sysml2Limits.CONSTRAINT_DEPTH] is treated as a top-level constraint. The checker
 * reports these cases; the layout never loops or drops elements because of them.
 */
internal class ParNesting private constructor(
    val constraints: Map<String, ConstraintDefinition>,
    private val ownerOf: Map<String, String>,
    private val childrenOf: Map<String, List<String>>,
) {
    fun ownerOf(id: String): String? = ownerOf[id]

    fun childrenOf(id: String): List<String> = childrenOf[id].orEmpty()

    fun isNested(id: String): Boolean = id in ownerOf

    fun rootOf(id: String): String {
        var cur = id
        var steps = 0
        while (steps++ <= Sysml2Limits.CONSTRAINT_DEPTH + 1) cur = ownerOf[cur] ?: return cur
        return cur
    }

    fun hasNesting(): Boolean = ownerOf.isNotEmpty()

    companion object {
        fun of(
            model: Sysml2Model,
            visibleIds: Collection<String>,
        ): ParNesting {
            val visible = visibleIds.toSet()
            val all = LinkedHashMap<String, ConstraintDefinition>()
            model.definitions.filterIsInstance<ConstraintDefinition>().forEach { if (it.id in visible && it.id !in all) all[it.id] = it }
            val owner = LinkedHashMap<String, String>()
            for (c in all.values) {
                val o = c.ownerConstraintId ?: continue
                if (o !in all) continue
                // depth with cycle/step guard
                var depth = 1
                var cur: String? = o
                val seen = hashSetOf(c.id)
                var valid = true
                while (cur != null) {
                    if (!seen.add(cur) || depth >= Sysml2Limits.CONSTRAINT_DEPTH) {
                        valid = false
                        break
                    }
                    depth++
                    cur = all[cur]?.ownerConstraintId?.takeIf { it in all }
                }
                if (valid) owner[c.id] = o
            }
            val children = LinkedHashMap<String, MutableList<String>>()
            for ((child, o) in owner) children.getOrPut(o) { mutableListOf() } += child
            return ParNesting(constraints = all, ownerOf = owner, childrenOf = children)
        }
    }
}

/** A resolved binding endpoint: the visible node plus, for constraints, the addressed parameter (if it exists). */
internal data class ParEnd(
    val nodeId: String,
    val parameterIndex: Int?,
)

internal fun parResolveEnd(
    endpoint: String,
    visibleIds: Set<String>,
    nesting: ParNesting,
): ParEnd? {
    val id = visibleIds.filter { endpoint == it || endpoint.startsWith("$it::") }.maxByOrNull { it.length } ?: return null
    val c = nesting.constraints[id] ?: return ParEnd(nodeId = id, parameterIndex = null)
    val rest = endpoint.removePrefix(id).removePrefix("::")
    val idx = c.parameters.indexOfFirst { it.name == rest }.takeIf { it >= 0 && rest.isNotEmpty() }
    return ParEnd(nodeId = id, parameterIndex = idx)
}

/** Size and placement of a constraint box including its nested children (children relative to the box origin). */
internal data class ParBox(
    val size: Size,
    val children: Map<String, Rect>,
)

internal object ParCompositeLayout {
    const val CHANNEL_H: Float = 30f
    const val SIDE_ZONE: Float = 28f
    const val GUTTER: Float = 28f
    const val BOTTOM_PAD: Float = 20f
    const val APPROACH: Float = 8f
    const val LANE_X0: Float = 6f
    const val LANE_STEP: Float = 4f
    const val CORNER_RADIUS: Float = 0f

    fun box(
        id: String,
        model: Sysml2Model,
        nesting: ParNesting,
        ownSize: (String) -> Size,
        memo: MutableMap<String, ParBox> = HashMap(),
    ): ParBox {
        memo[id]?.let { return it }
        val own = ownSize(id)
        val kids = orderChildren(model = model, nesting = nesting, owner = id)
        val result =
            if (kids.isEmpty()) {
                ParBox(size = own, children = emptyMap())
            } else {
                val boxes = kids.associateWith { box(id = it, model = model, nesting = nesting, ownSize = ownSize, memo = memo) }
                val rowW = boxes.values.sumOf { it.size.width.toDouble() }.toFloat() + GUTTER * (kids.size - 1)
                val w = maxOf(own.width, rowW + 2 * SIDE_ZONE)
                val y0 = own.height + CHANNEL_H
                var x = (w - rowW) / 2f
                val rects = LinkedHashMap<String, Rect>()
                for (k in kids) {
                    val b = boxes.getValue(k)
                    rects[k] = Rect(origin = Point(x = x, y = y0), size = b.size)
                    x += b.size.width + GUTTER
                }
                val h = y0 + (boxes.values.maxOfOrNull { it.size.height } ?: 0f) + BOTTOM_PAD
                ParBox(size = Size(width = w, height = h), children = rects)
            }
        memo[id] = result
        return result
    }

    /** Declaration order, refined so that a constraint producing a value comes before the one consuming it. */
    private fun orderChildren(
        model: Sysml2Model,
        nesting: ParNesting,
        owner: String,
    ): List<String> {
        val kids = nesting.childrenOf(owner)
        if (kids.size < 2) return kids
        val index = kids.withIndex().associate { it.value to it.index }
        val deps = kids.associateWith { mutableSetOf<String>() } // consumer -> producers
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val s = kids.firstOrNull { b.sourceEndId.startsWith("$it::") } ?: continue
            val t = kids.firstOrNull { b.targetEndId.startsWith("$it::") } ?: continue
            if (s == t) continue
            val sOut = pinDir(nesting = nesting, endpoint = b.sourceEndId, owner = s)
            val tOut = pinDir(nesting = nesting, endpoint = b.targetEndId, owner = t)
            when {
                sOut && !tOut -> deps.getValue(t) += s
                tOut && !sOut -> deps.getValue(s) += t
            }
        }
        val out = mutableListOf<String>()
        val remaining = kids.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { k -> deps.getValue(k).none { it in remaining } } ?: remaining.minBy { index.getValue(it) }
            out += next
            remaining -= next
        }
        return out
    }

    private fun pinDir(
        nesting: ParNesting,
        endpoint: String,
        owner: String,
    ): Boolean {
        val c = nesting.constraints.getValue(owner)
        val p = c.parameters.firstOrNull { endpoint == "${c.id}::${it.name}" } ?: return false
        return p.direction != ConstraintParameterDirection.In
    }

    /** Absolute rectangles of every visible constraint of the composite rooted at [rootId]. */
    fun absoluteRects(
        rootId: String,
        rootBounds: Rect,
        model: Sysml2Model,
        nesting: ParNesting,
        ownSize: (String) -> Size,
    ): Map<String, Rect> {
        val memo = HashMap<String, ParBox>()
        val out = LinkedHashMap<String, Rect>()

        fun walk(
            id: String,
            at: Rect,
        ) {
            out[id] = at
            val b = box(id = id, model = model, nesting = nesting, ownSize = ownSize, memo = memo)
            for ((k, r) in b.children) {
                walk(id = k, at = Rect(origin = Point(x = at.origin.x + r.origin.x, y = at.origin.y + r.origin.y), size = r.size))
            }
        }
        walk(id = rootId, at = rootBounds)
        return out
    }

    /**
     * Adds nested constraint boxes (with their pin positions) and the routes of all bindings that stay inside one
     * composite to [layout]. Idempotent: constraints already present in [layout.nodes] are left alone.
     */
    fun enrich(
        model: Sysml2Model,
        visibleIds: List<String>,
        layout: LayoutResult,
        ownSize: (String) -> Size,
    ): LayoutResult {
        val nesting = ParNesting.of(model = model, visibleIds = visibleIds)
        if (!nesting.hasNesting()) return layout
        val nodes = LinkedHashMap(layout.nodes)
        val edges = LinkedHashMap(layout.edges)
        val rects = LinkedHashMap<String, Rect>()
        for (id in nesting.constraints.keys) {
            if (nesting.isNested(id)) continue
            val nl = layout.nodes[NodeId(id)] ?: continue
            rects += absoluteRects(rootId = id, rootBounds = nl.bounds, model = model, nesting = nesting, ownSize = ownSize)
        }
        for ((id, r) in rects) {
            if (!nesting.isNested(id)) continue
            val c = nesting.constraints.getValue(id)
            val ports = LinkedHashMap<PortId, Point>()
            c.parameters.forEachIndexed { i, p ->
                ports[ParConstraintGeometry.portId(constraint = c, parameter = p)] =
                    ParConstraintGeometry.pinPoint(constraint = c, index = i, bounds = r)
            }
            if (NodeId(id) !in nodes) nodes[NodeId(id)] = NodeLayout(bounds = r, ports = ports)
        }
        val visible = visibleIds.toSet()
        var lane = 0
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val a = parResolveEnd(endpoint = b.sourceEndId, visibleIds = visible, nesting = nesting) ?: continue
            val z = parResolveEnd(endpoint = b.targetEndId, visibleIds = visible, nesting = nesting) ?: continue
            if (a.nodeId !in nesting.constraints || z.nodeId !in nesting.constraints) continue
            if (nesting.rootOf(a.nodeId) != nesting.rootOf(z.nodeId)) continue
            if (a.nodeId == z.nodeId) continue
            val ra = rects[a.nodeId] ?: continue
            val rz = rects[z.nodeId] ?: continue
            val ca = nesting.constraints.getValue(a.nodeId)
            val cz = nesting.constraints.getValue(z.nodeId)
            val pa = a.parameterIndex?.let { ParConstraintGeometry.pinPoint(constraint = ca, index = it, bounds = ra) } ?: centre(ra)
            val pz = z.parameterIndex?.let { ParConstraintGeometry.pinPoint(constraint = cz, index = it, bounds = rz) } ?: centre(rz)
            val sa = a.parameterIndex?.let { ParConstraintGeometry.pinSide(ca.parameters[it].direction) }
            val sz = z.parameterIndex?.let { ParConstraintGeometry.pinSide(cz.parameters[it].direction) }
            val k = lane++
            val pts =
                innerRoute(
                    a = Inner(id = a.nodeId, rect = ra, pin = pa, side = sa, index = a.parameterIndex, c = ca),
                    z = Inner(id = z.nodeId, rect = rz, pin = pz, side = sz, index = z.parameterIndex, c = cz),
                    nesting = nesting,
                    rects = rects,
                    ownSize = ownSize,
                    lane = k,
                )
            edges[EdgeId(b.id)] =
                EdgeRoute.OrthogonalRounded(
                    source = pts.first(),
                    target = pts.last(),
                    waypoints = pts.drop(1).dropLast(1),
                    cornerRadiusPx = CORNER_RADIUS,
                )
        }
        return layout.copy(nodes = nodes, edges = edges)
    }

    private data class Inner(
        val id: String,
        val rect: Rect,
        val pin: Point,
        val side: PortSide?,
        val index: Int?,
        val c: ConstraintDefinition,
    )

    private fun centre(r: Rect) = Point(x = r.origin.x + r.size.width / 2f, y = r.origin.y + r.size.height / 2f)

    private fun innerRoute(
        a: Inner,
        z: Inner,
        nesting: ParNesting,
        rects: Map<String, Rect>,
        ownSize: (String) -> Size,
        lane: Int,
    ): List<Point> {
        val raw: List<Point> =
            when {
                // Pins unknown: fall back to a straight line between the box centres (checker warns about the binding).
                a.side == null || z.side == null -> listOf(a.pin, z.pin)
                // owner pin -> child pin (or the reverse): follow the owner's inner lane, then the channel
                nesting.ownerOf(z.id) == a.id -> ownerToChild(owner = a, child = z, ownSize = ownSize, lane = lane)
                nesting.ownerOf(a.id) == z.id -> ownerToChild(owner = z, child = a, ownSize = ownSize, lane = lane).reversed()
                nesting.ownerOf(a.id) == nesting.ownerOf(z.id) && nesting.ownerOf(a.id) != null -> {
                    val owner = rects.getValue(nesting.ownerOf(a.id)!!)
                    siblings(a = a, z = z, ownerRect = owner, ownerHeader = ownSize(nesting.ownerOf(a.id)!!).height, lane = lane)
                }
                else -> listOf(a.pin, z.pin)
            }
        return simplify(raw)
    }

    private fun approach(
        p: Inner,
        pin: Point = p.pin,
    ): Point = Point(x = if (p.side == PortSide.West) pin.x - APPROACH else pin.x + APPROACH, y = pin.y)

    private fun channelY(
        ownerRect: Rect,
        ownerHeader: Float,
        lane: Int,
    ): Float = ownerRect.origin.y + ownerHeader + 8f + (lane % 5) * 5f

    private fun ownerToChild(
        owner: Inner,
        child: Inner,
        ownSize: (String) -> Size,
        lane: Int,
    ): List<Point> {
        val k = minOf(owner.index ?: 0, 2)
        val laneX =
            if (owner.side ==
                PortSide.West
            ) {
                owner.rect.origin.x + LANE_X0 + LANE_STEP * k
            } else {
                owner.rect.origin.x + owner.rect.size.width - LANE_X0 -
                    LANE_STEP * k
            }
        val ch = channelY(ownerRect = owner.rect, ownerHeader = ownSize(owner.id).height, lane = lane)
        val app = approach(p = child)
        return listOf(owner.pin, Point(x = laneX, y = owner.pin.y), Point(x = laneX, y = ch), Point(x = app.x, y = ch), app, child.pin)
    }

    private fun siblings(
        a: Inner,
        z: Inner,
        ownerRect: Rect,
        ownerHeader: Float,
        lane: Int,
    ): List<Point> {
        val leftToRight = a.rect.origin.x < z.rect.origin.x
        val adjacentGap =
            if (leftToRight) {
                z.rect.origin.x - (a.rect.origin.x + a.rect.size.width)
            } else {
                a.rect.origin.x -
                    (z.rect.origin.x + z.rect.size.width)
            }
        val facing =
            if (leftToRight) a.side == PortSide.East && z.side == PortSide.West else a.side == PortSide.West && z.side == PortSide.East
        if (facing && adjacentGap in 1f..(GUTTER + 0.5f)) {
            val mid = if (leftToRight) a.rect.origin.x + a.rect.size.width + adjacentGap / 2f else a.rect.origin.x - adjacentGap / 2f
            return listOf(a.pin, Point(x = mid, y = a.pin.y), Point(x = mid, y = z.pin.y), z.pin)
        }
        val ch = channelY(ownerRect = ownerRect, ownerHeader = ownerHeader, lane = lane)
        val pa = approach(p = a)
        val pz = approach(p = z)
        return listOf(a.pin, pa, Point(x = pa.x, y = ch), Point(x = pz.x, y = ch), pz, z.pin)
    }

    /** Drops duplicate and collinear points. */
    private fun simplify(points: List<Point>): List<Point> {
        val dedup = mutableListOf<Point>()
        for (p in points) if (dedup.isEmpty() || dedup.last() != p) dedup += p
        if (dedup.size < 3) return dedup.ifEmpty { points }
        val out = mutableListOf(dedup.first())
        for (i in 1 until dedup.size - 1) {
            val p = out.last()
            val q = dedup[i]
            val r = dedup[i + 1]
            val collinear = (p.x == q.x && q.x == r.x) || (p.y == q.y && q.y == r.y)
            if (!collinear) out += q
        }
        out += dedup.last()
        return out
    }
}
