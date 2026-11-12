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
import dev.kuml.layout.bridge.port.PortAttachment
import dev.kuml.layout.bridge.port.PortEdgeClamp
import dev.kuml.layout.bridge.port.PortFace
import dev.kuml.sysml2.AttributeDefinition
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

    /** Baseline of the first expression line (when there is an expression). */
    public const val EXPRESSION_BASELINE: Float = 56f

    /** Line pitch of the (wrapped) expression. */
    public const val EXPR_LINE_H: Float = 14f

    /** An expression line is wrapped at this many characters. */
    public const val EXPRESSION_WRAP_CHARS: Int = 60

    /** Distance between a divider and the first parameter baseline (keeps ascenders 4 px below the divider). */
    public const val PARAM_FIRST_BASELINE_OFFSET: Float = 16f

    /** Row height of the parameter list (pins of neighbouring rows stay 10 px apart). */
    public const val PARAM_ROW_H: Float = 18f

    /** The pin sits this far above the parameter baseline (middle of the x-height of 11 px text). */
    public const val PIN_BASELINE_OFFSET: Float = 4f

    /** Edge length of the square pin marker. */
    public const val PIN_SIZE: Float = 8f

    /** Descender allowance below the last baseline. */
    private const val DESCENDER: Float = 4f

    /** Padding below the last text line of a box. */
    public const val BOX_BOTTOM_PAD: Float = 8f

    /** Left indent of the parameter text; leaves room for the inner routing lanes. */
    public const val PARAM_TEXT_INDENT: Float = 18f

    /**
     * Left indent of the parameter text of [constraint]: at least [PARAM_TEXT_INDENT], wider when many inward-binding
     * pins put corridor columns (8 px pitch) on the west side of an owner box. Depends on [model] only, so the SVG
     * renderer, the size provider and the label geometry agree.
     */
    public fun paramTextIndent(
        model: Sysml2Model,
        constraint: ConstraintDefinition,
    ): Float {
        val childIds =
            model.definitions
                .filterIsInstance<ConstraintDefinition>()
                .filter { it.ownerConstraintId == constraint.id }
                .map { it.id }
        if (childIds.isEmpty()) return PARAM_TEXT_INDENT
        val west = HashSet<String>()
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            for ((own, other) in listOf(b.sourceEndId to b.targetEndId, b.targetEndId to b.sourceEndId)) {
                val p = constraint.parameters.firstOrNull { own == "${constraint.id}::${it.name}" } ?: continue
                if (pinSide(p.direction) == PortSide.West && childIds.any { other.startsWith("$it::") }) west += p.name
            }
        }
        if (west.isEmpty()) return PARAM_TEXT_INDENT
        return maxOf(PARAM_TEXT_INDENT, ParCompositeLayout.LANE_X0 + (west.size - 1) * ParCompositeLayout.LANE_PITCH_X + 6f)
    }

    /** Average width of an 11 pt monospace glyph (expression compartment). */
    public const val EXPRESSION_CHAR_PX: Float = 7.2f

    /** Gap between the last expression baseline and the divider below it. */
    private const val DIVIDER_BELOW_EXPRESSION: Float = 13f

    /**
     * Wraps [expr] into lines of at most [EXPRESSION_WRAP_CHARS] characters. Deterministic single pass, no regex.
     * Break preference within a window: before `&&`/`||`, then before a comparison/additive operator, then at a
     * space, otherwise a hard cut. Only whitespace is dropped, so the lines concatenated equal [expr] without blanks.
     */
    public fun wrapExpression(expr: String): List<String> {
        val text = expr.trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break
            val remaining = text.length - start
            if (remaining <= EXPRESSION_WRAP_CHARS) {
                out += text.substring(startIndex = start).trimEnd()
                break
            }
            val cut = breakPoint(text = text, start = start)
            out += text.substring(startIndex = start, endIndex = cut).trimEnd()
            start = cut
        }
        return out
    }

    /** Absolute index at which the line beginning at [start] ends (exclusive); always > [start]. */
    private fun breakPoint(
        text: String,
        start: Int,
    ): Int {
        val limit = minOf(text.length - 1, start + EXPRESSION_WRAP_CHARS)
        var logical = -1
        var comparison = -1
        var space = -1
        var p = start + 1
        while (p <= limit) {
            val c = text[p]
            val n = if (p + 1 < text.length) text[p + 1] else ' '
            when {
                (c == '&' && n == '&') || (c == '|' && n == '|') -> logical = p
                (c == '=' && n == '=') || (c == '<' || c == '>') || c == '+' || c == '-' -> comparison = p
                c == ' ' -> space = p
            }
            p++
        }
        return when {
            logical > 0 -> logical
            comparison > 0 -> comparison
            space > 0 -> space
            else -> start + EXPRESSION_WRAP_CHARS
        }
    }

    /** The wrapped, length-capped expression lines of [constraint]; empty when it has no expression. */
    public fun expressionLinesOf(constraint: ConstraintDefinition): List<String> {
        val e = constraint.expression
        val capped = if (e.length > Sysml2Limits.TEXT) e.take(Sysml2Limits.TEXT - 1) + "…" else e
        return wrapExpression(capped)
    }

    /** y of the divider above the parameter list when the expression has [lines] lines. */
    public fun dividerAfterExpressionY(lines: Int): Float =
        EXPRESSION_BASELINE + (maxOf(lines, 1) - 1) * EXPR_LINE_H + DIVIDER_BELOW_EXPRESSION

    /** Baseline of parameter row [index] for a box whose expression has [expressionLines] lines. */
    public fun paramBaseline(
        expressionLines: Int,
        index: Int,
    ): Float {
        val divider = if (expressionLines <= 0) FIRST_DIVIDER_Y else dividerAfterExpressionY(expressionLines)
        return divider + PARAM_FIRST_BASELINE_OFFSET + index * PARAM_ROW_H
    }

    /** y of the pin of parameter row [index], relative to the top of the box. */
    public fun pinY(
        expressionLines: Int,
        index: Int,
    ): Float = paramBaseline(expressionLines = expressionLines, index = index) - PIN_BASELINE_OFFSET

    /** Height of a constraint box (without nested children). */
    public fun contentHeight(constraint: ConstraintDefinition): Float {
        val lines = expressionLinesOf(constraint).size
        val n = constraint.parameters.size
        return if (n > 0) {
            paramBaseline(expressionLines = lines, index = n - 1) + DESCENDER + BOX_BOTTOM_PAD
        } else if (lines > 0) {
            dividerAfterExpressionY(lines) + BOX_BOTTOM_PAD
        } else {
            FIRST_DIVIDER_Y + BOX_BOTTOM_PAD
        }
    }

    /** Text of one parameter row, e.g. `«in» pMix : Druck [Pa]`. Only the type name is capped, never the unit. */
    public fun parameterLine(
        p: ConstraintParameter,
        unitOf: (String) -> String?,
    ): String {
        val stereotype =
            when (p.direction) {
                ConstraintParameterDirection.In -> "«in»"
                ConstraintParameterDirection.Out -> "«out»"
                ConstraintParameterDirection.Inout -> "«inout»"
            }
        val typeSuffix =
            p.typeId?.let { t ->
                val unit = unitOf(t)?.takeIf { it.isNotBlank() }?.take(Sysml2Limits.TEXT)
                " : ${t.take(Sysml2Limits.TEXT)}" + (unit?.let { " [$it]" } ?: "")
            } ?: ""
        return "$stereotype ${p.name.take(Sysml2Limits.TEXT)}$typeSuffix"
    }

    /** Unit lookup over the `AttributeDefinition`s of [model]. */
    public fun unitLookup(model: Sysml2Model): (String) -> String? {
        val units =
            model.definitions
                .filterIsInstance<AttributeDefinition>()
                .filter { it.unit != null }
                .associate { it.id to it.unit }
        return { id -> units[id] }
    }

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
        val y = bounds.origin.y + pinY(expressionLines = expressionLinesOf(constraint).size, index = index)
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
    /** For a part end: index of the addressed attribute row (its position in the part's feature list), else `null`. */
    val attributeIndex: Int? = null,
)

internal fun parResolveEnd(
    endpoint: String,
    visibleIds: Set<String>,
    nesting: ParNesting,
    partAttributes: Map<String, List<String>> = emptyMap(),
): ParEnd? {
    val id = visibleIds.filter { endpoint == it || endpoint.startsWith("$it::") }.maxByOrNull { it.length } ?: return null
    val c =
        nesting.constraints[id] ?: run {
            val rest = endpoint.removePrefix(id).removePrefix("::")
            val idx = partAttributes[id]?.indexOf(rest)?.takeIf { it >= 0 && rest.isNotEmpty() }
            return ParEnd(nodeId = id, parameterIndex = null, attributeIndex = idx)
        }
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

    /**
     * Distance of the first approach column to the pin centre. The line ends at the pin edge (half the 8 px pin
     * square closer), so 16 leaves a visible stub of 12 px, longer than an arrowhead.
     */
    const val APPROACH: Float = 16f

    /** Minimum distance of a nested-binding lane to the inner edge of its owner box and to the sides of a sibling child. */
    const val PAR_LANE_CLEARANCE: Float = 12f
    const val LANE_X0: Float = PAR_LANE_CLEARANCE

    /** Pitch of vertical lane columns (x axis): 12 px keeps two parallel lines visibly apart. */
    const val LANE_PITCH_X: Float = 12f

    /** Pitch of horizontal lanes (y axis): label height 13 px plus 3 px air, so a label never sits on the neighbouring lane. */
    const val LANE_PITCH_Y: Float = 16f
    const val CORNER_RADIUS: Float = 0f

    /** Width of a side zone: room for [corridors] owner corridor columns and [approaches] approach columns of the edge child. */
    private fun zone(
        corridors: Int,
        approaches: Int,
    ): Float {
        val base = if (corridors > 0) LANE_X0 + (corridors - 1) * LANE_PITCH_X + LANE_PITCH_X else LANE_PITCH_X
        return maxOf(SIDE_ZONE, base + approaches * LANE_PITCH_X)
    }

    fun box(
        id: String,
        model: Sysml2Model,
        nesting: ParNesting,
        plan: ParLanePlan,
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
                val boxes =
                    kids.associateWith {
                        box(
                            id = it,
                            model = model,
                            nesting = nesting,
                            plan = plan,
                            ownSize = ownSize,
                            memo = memo,
                        )
                    }
                val gaps =
                    (0 until kids.size - 1).map { i ->
                        val columns =
                            plan.approachCount(child = kids[i], side = PortSide.East) +
                                plan.shortCount(owner = id, gapIndex = i) +
                                plan.approachCount(child = kids[i + 1], side = PortSide.West)
                        // short lanes run from the left box (clearance first), approach columns from the right box
                        // (APPROACH first); one pitch between the two groups keeps every column pair at LANE_PITCH_X.
                        maxOf(GUTTER, PAR_LANE_CLEARANCE + APPROACH + LANE_PITCH_X * (columns - 1))
                    }
                val rowW = boxes.values.sumOf { it.size.width.toDouble() }.toFloat() + gaps.sum()
                val zoneL =
                    zone(
                        corridors = plan.corridorCount(owner = id, side = PortSide.West),
                        approaches = plan.approachCount(child = kids.first(), side = PortSide.West),
                    )
                val zoneR =
                    zone(
                        corridors = plan.corridorCount(owner = id, side = PortSide.East),
                        approaches = plan.approachCount(child = kids.last(), side = PortSide.East),
                    )
                val w = maxOf(own.width, zoneL + rowW + zoneR)
                val channelH = maxOf(CHANNEL_H, 8f + plan.channelCount(owner = id) * LANE_PITCH_Y + 8f)
                val y0 = own.height + channelH
                var x = zoneL + (w - zoneL - rowW - zoneR) / 2f
                val rects = LinkedHashMap<String, Rect>()
                kids.forEachIndexed { i, k ->
                    val b = boxes.getValue(k)
                    rects[k] = Rect(origin = Point(x = x, y = y0), size = b.size)
                    x += b.size.width + (gaps.getOrNull(i) ?: 0f)
                }
                val h = y0 + (boxes.values.maxOfOrNull { it.size.height } ?: 0f) + BOTTOM_PAD
                ParBox(size = Size(width = w, height = h), children = rects)
            }
        memo[id] = result
        return result
    }

    /** Declaration order, refined so that a constraint producing a value comes before the one consuming it. */
    internal fun orderChildren(
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
        plan: ParLanePlan,
        ownSize: (String) -> Size,
    ): Map<String, Rect> {
        val memo = HashMap<String, ParBox>()
        val out = LinkedHashMap<String, Rect>()

        fun walk(
            id: String,
            at: Rect,
        ) {
            out[id] = at
            val b = box(id = id, model = model, nesting = nesting, plan = plan, ownSize = ownSize, memo = memo)
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
        if (!nesting.hasNesting()) return clampPins(model = model, visibleIds = visibleIds, layout = layout)
        val plan = ParLanePlan.of(model = model, nesting = nesting, visible = visibleIds.toSet())
        val routed =
            enrichNested(model = model, visibleIds = visibleIds, layout = layout, ownSize = ownSize, nesting = nesting, plan = plan)
        return applyPinShare(layout = clampPins(model = model, visibleIds = visibleIds, layout = routed), plan = plan)
    }

    /**
     * Shifts the end stubs of bindings that share a pin by a few px (the pin square is 8 px high), after
     * [clampPins] has snapped every end to the pin centre. Only the first and last segment move, and only when
     * they are horizontal, so the pin end stays on the pin face.
     */
    private fun applyPinShare(
        layout: LayoutResult,
        plan: ParLanePlan,
    ): LayoutResult {
        val edges = LinkedHashMap(layout.edges)
        var changed = false
        for (id in plan.routedIds()) {
            val s0 = plan.share(bindingId = id, end = 0)
            val s1 = plan.share(bindingId = id, end = 1)
            if (s0 == 0f && s1 == 0f) continue
            val route = edges[EdgeId(id)] as? EdgeRoute.OrthogonalRounded ?: continue
            val pts = (listOf(route.source) + route.waypoints + listOf(route.target)).toMutableList()
            if (pts.size < 4) continue
            val n = pts.size
            if (s0 != 0f && pts[0].y == pts[1].y) {
                pts[0] = Point(x = pts[0].x, y = pts[0].y + s0)
                pts[1] = Point(x = pts[1].x, y = pts[1].y + s0)
            }
            if (s1 != 0f && pts[n - 1].y == pts[n - 2].y) {
                pts[n - 1] = Point(x = pts[n - 1].x, y = pts[n - 1].y + s1)
                pts[n - 2] = Point(x = pts[n - 2].x, y = pts[n - 2].y + s1)
            }
            edges[EdgeId(id)] =
                EdgeRoute.OrthogonalRounded(
                    source = pts.first(),
                    target = pts.last(),
                    waypoints = pts.drop(1).dropLast(1),
                    cornerRadiusPx = route.cornerRadiusPx,
                )
            changed = true
        }
        return if (changed) layout.copy(edges = edges) else layout
    }

    /**
     * PAL 2C (P-IBD-08): lets every binding edge end on the outer border of its pin square, perpendicular to the box
     * side, via the central [PortEdgeClamp]. Bindings carry no arrowhead, so no stub is forced at a pin (PAL 2C-b1).
     * Idempotent; edges without a pin end stay untouched.
     */
    fun clampPins(
        model: Sysml2Model,
        visibleIds: List<String>,
        layout: LayoutResult,
    ): LayoutResult {
        val nesting = ParNesting.of(model = model, visibleIds = visibleIds)
        if (nesting.constraints.isEmpty()) return layout
        val visible = visibleIds.toSet()
        val half = ParConstraintGeometry.PIN_SIZE / 2f

        fun descendantOf(
            id: String,
            ancestor: String,
        ): Boolean {
            var cur = nesting.ownerOf(id)
            var steps = 0
            while (cur != null && steps++ <= Sysml2Limits.CONSTRAINT_DEPTH + 1) {
                if (cur == ancestor) return true
                cur = nesting.ownerOf(cur)
            }
            return false
        }

        fun attach(
            end: ParEnd,
            other: ParEnd,
        ): PortAttachment? {
            val idx = end.parameterIndex ?: return null
            val c = nesting.constraints[end.nodeId] ?: return null
            val bounds = layout.nodes[NodeId(end.nodeId)]?.bounds ?: return null
            val pin = ParConstraintGeometry.pinPoint(constraint = c, index = idx, bounds = bounds)
            val west = ParConstraintGeometry.pinSide(c.parameters[idx].direction) == PortSide.West
            val inner = other.nodeId != end.nodeId && descendantOf(id = other.nodeId, ancestor = end.nodeId)
            val faceLeft = if (inner) !west else west
            return PortAttachment(
                rect = Rect(origin = Point(x = pin.x - half, y = pin.y - half), size = Size(width = 2f * half, height = 2f * half)),
                face = if (faceLeft) PortFace.Left else PortFace.Right,
            )
        }

        val edges = LinkedHashMap(layout.edges)
        var changed = false
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val route = edges[EdgeId(b.id)] ?: continue
            val a = parResolveEnd(endpoint = b.sourceEndId, visibleIds = visible, nesting = nesting) ?: continue
            val z = parResolveEnd(endpoint = b.targetEndId, visibleIds = visible, nesting = nesting) ?: continue
            val sa = attach(end = a, other = z)
            val sz = attach(end = z, other = a)
            if (sa == null && sz == null) continue
            edges[EdgeId(b.id)] =
                PortEdgeClamp.clamp(
                    route = route,
                    source = sa,
                    target = sz,
                    sourceArrowhead = false,
                    targetArrowhead = false,
                    cornerRadiusPx = CORNER_RADIUS,
                )
            changed = true
        }
        return if (changed) layout.copy(edges = edges) else layout
    }

    private fun enrichNested(
        model: Sysml2Model,
        visibleIds: List<String>,
        layout: LayoutResult,
        ownSize: (String) -> Size,
        nesting: ParNesting,
        plan: ParLanePlan,
    ): LayoutResult {
        val nodes = LinkedHashMap(layout.nodes)
        val edges = LinkedHashMap(layout.edges)
        val rects = LinkedHashMap<String, Rect>()
        for (id in nesting.constraints.keys) {
            if (nesting.isNested(id)) continue
            val nl = layout.nodes[NodeId(id)] ?: continue
            rects += absoluteRects(rootId = id, rootBounds = nl.bounds, model = model, nesting = nesting, plan = plan, ownSize = ownSize)
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
            val routed = plan.route(b.id)
            val pts =
                innerRoute(
                    a =
                        Inner(
                            id = a.nodeId,
                            rect = ra,
                            pin = pa,
                            side = sa,
                            index = a.parameterIndex,
                            share = plan.share(bindingId = b.id, end = 0),
                        ),
                    z =
                        Inner(
                            id = z.nodeId,
                            rect = rz,
                            pin = pz,
                            side = sz,
                            index = z.parameterIndex,
                            share = plan.share(bindingId = b.id, end = 1),
                        ),
                    routed = routed,
                    ownerRect = routed?.let { rects[it.owner] },
                    plan = plan,
                    ownSize = ownSize,
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
        val share: Float,
    )

    private fun centre(r: Rect) = Point(x = r.origin.x + r.size.width / 2f, y = r.origin.y + r.size.height / 2f)

    private fun innerRoute(
        a: Inner,
        z: Inner,
        routed: ParRouted?,
        ownerRect: Rect?,
        plan: ParLanePlan,
        ownSize: (String) -> Size,
    ): List<Point> {
        val raw: List<Point> =
            when {
                // Pins unknown or binding outside the plan: straight line between the pins (the checker warns about it).
                a.side == null || z.side == null || a.index == null || z.index == null || routed == null -> listOf(a.pin, z.pin)
                routed.kind == ParRouteKind.OwnerToChild && routed.aIsOwner ->
                    ownerToChild(owner = a, child = z, routed = routed, plan = plan, ownSize = ownSize)
                routed.kind == ParRouteKind.OwnerToChild ->
                    ownerToChild(owner = z, child = a, routed = routed, plan = plan, ownSize = ownSize).reversed()
                routed.kind == ParRouteKind.SiblingShort -> siblingShort(a = a, z = z, routed = routed, plan = plan)
                else -> siblingLong(a = a, z = z, routed = routed, ownerRect = ownerRect ?: a.rect, plan = plan, ownSize = ownSize)
            }
        return simplify(raw)
    }

    /** Approach point of a child pin: column [LANE_PITCH_X] apart per rank, ending in a horizontal stub to the pin. */
    private fun approach(
        p: Inner,
        plan: ParLanePlan,
    ): Point {
        val r = plan.approachRank(child = p.id, side = p.side!!, paramIndex = p.index!!)
        val dx = APPROACH + r * LANE_PITCH_X
        return Point(x = if (p.side == PortSide.West) p.pin.x - dx + p.share else p.pin.x + dx + p.share, y = p.pin.y)
    }

    private fun channelY(
        ownerRect: Rect,
        ownerHeader: Float,
        lane: Int,
    ): Float = ownerRect.origin.y + ownerHeader + 8f + lane * LANE_PITCH_Y

    private fun ownerToChild(
        owner: Inner,
        child: Inner,
        routed: ParRouted,
        plan: ParLanePlan,
        ownSize: (String) -> Size,
    ): List<Point> {
        val j = plan.corridorRank(owner = owner.id, side = owner.side!!, paramIndex = owner.index!!) ?: 0
        val laneX =
            if (owner.side == PortSide.West) {
                owner.rect.origin.x + LANE_X0 + LANE_PITCH_X * j + owner.share
            } else {
                owner.rect.origin.x + owner.rect.size.width - LANE_X0 - LANE_PITCH_X * j + owner.share
            }
        val ch = channelY(ownerRect = owner.rect, ownerHeader = ownSize(owner.id).height, lane = plan.channelLane(routed.id))
        val app = approach(p = child, plan = plan)
        return listOf(owner.pin, Point(x = laneX, y = owner.pin.y), Point(x = laneX, y = ch), Point(x = app.x, y = ch), app, child.pin)
    }

    private fun siblingShort(
        a: Inner,
        z: Inner,
        routed: ParRouted,
        plan: ParLanePlan,
    ): List<Point> {
        val left = if (a.rect.origin.x < z.rect.origin.x) a else z
        val nE = plan.approachCount(child = left.id, side = PortSide.East)
        val mid = left.rect.origin.x + left.rect.size.width + PAR_LANE_CLEARANCE + LANE_PITCH_X * (nE + plan.shortRank(routed.id))
        return listOf(a.pin, Point(x = mid, y = a.pin.y), Point(x = mid, y = z.pin.y), z.pin)
    }

    private fun siblingLong(
        a: Inner,
        z: Inner,
        routed: ParRouted,
        ownerRect: Rect,
        plan: ParLanePlan,
        ownSize: (String) -> Size,
    ): List<Point> {
        val ch = channelY(ownerRect = ownerRect, ownerHeader = ownSize(routed.owner).height, lane = plan.channelLane(routed.id))
        val pa = approach(p = a, plan = plan)
        val pz = approach(p = z, plan = plan)
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
