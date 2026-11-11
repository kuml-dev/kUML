package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The stem of a lifeline: the vertical line at [x] from [top] (below the head) to [bottom]. */
public data class SeqStem(
    val x: Float,
    val top: Float,
    val bottom: Float,
)

/**
 * Everything a sequence diagram draws, in layout coordinates, as the note placement sees it (Nacharbeit 39).
 *
 * @property stems lifeline stems by lifeline id; also the anchor geometry of a participant
 * @property heads head boxes by lifeline id
 * @property blockers further solid rectangles: activation bars, `ref` frames, operator tags, guard labels, message
 *   labels, duration and time marks
 * @property frameLines outlines of combined fragments (polylines)
 * @property operandLines operand separators (polylines)
 * @property messageLines every drawn message path, including self loops and the arrows' shafts
 * @property messages anchor polylines of messages by message id (a note may be anchored to a message)
 * @property rowPitch height of one message row; bounds the vertical search of a note
 */
public data class SeqNoteScene(
    val stems: Map<String, SeqStem>,
    val heads: Map<String, Rect> = emptyMap(),
    val blockers: List<Rect> = emptyList(),
    val frameLines: List<List<Point>> = emptyList(),
    val operandLines: List<List<Point>> = emptyList(),
    val messageLines: List<List<Point>> = emptyList(),
    val messages: Map<String, List<Point>> = emptyMap(),
    val rowPitch: Float = 32f,
)

/** The numbers of the sequence note placement; no size constants live in the code. */
public data class SeqNotePolicy(
    /** Hard clearance between a note and every drawn element and every other note. */
    val clearance: Float = 16f,
    /** Gap between a note and the stem it is anchored to (inner columns). */
    val anchorSideGap: Float = 16f,
    /** Gap between the outermost drawn element and a margin column. */
    val marginGap: Float = 24f,
    /** Distance of a participant anchor below the head. */
    val stemInset: Float = 24f,
    /** Vertical search step. */
    val step: Float = 8f,
    /** Maximum vertical search distance in message rows. */
    val maxRowSteps: Int = 3,
    /** Clearance between a note and the anchor line of an earlier note. */
    val lineClearance: Float = 6f,
    /** Two parallel anchor lines / messages keep this distance. */
    val parallelTolerance: Float = 5f,
    /** Distance of the corridor of a message anchor below the message line. */
    val corridor: Float = 8f,
)

/**
 * Places the notes of a sequence diagram (Nacharbeit 39). Unlike [NotePlacer] there is no free-form candidate search:
 * the candidate columns are fixed (inner column right of the anchor, inner column left, right margin, left margin), the
 * vertical position starts at the anchor and moves at most [SeqNotePolicy.maxRowSteps] message rows. Every candidate
 * must keep [SeqNotePolicy.clearance] px from stems, heads, activation bars, fragment frames and tags, operand
 * separators, guards, message lines and labels, `ref` frames and other notes. The anchor line of a note is horizontal
 * (participant) or runs in a corridor below the message (message anchor); it never crosses a foreign note, head,
 * activation bar, tag, guard, label or operand separator. Crossings of foreign stems, messages and frame edges are
 * counted and minimised ([NotePlacementResult.crossings]).
 *
 * When no column is admissible the note goes into the right margin below everything else and is reported in
 * [NotePlacementResult.degraded]; a note rectangle never overlaps anything.
 *
 * A note anchored on several lifelines always goes to the margin of the side of its outermost lifeline; one line per
 * anchor runs horizontally to the stem (crossing foreign stems on the way, which are counted).
 */
public object SeqNotePlacer {
    private sealed interface Target {
        val linkId: String
        val y: Float
    }

    private class StemTarget(
        override val linkId: String,
        val id: String,
        val stem: SeqStem,
        override val y: Float,
    ) : Target

    private class MessageTarget(
        override val linkId: String,
        val id: String,
        val pts: List<Point>,
    ) : Target {
        val mid: Point = polylineMidpoint(pts)
        override val y: Float get() = mid.y
    }

    private class Plan(
        val rect: Rect,
        val routes: List<List<Point>>,
        val crossings: Int,
    ) {
        /** Inner column kind (1 right, 2 left) this plan was found in; 0 for margin columns and the fallback. */
        var columnKind: Int = 0
    }

    private class Column(
        val kind: Int,
        val left: Float,
        val right: Float,
    )

    /** Places [notes]; [layout] must contain the lifeline head nodes. */
    public fun place(
        layout: LayoutResult,
        notes: List<NotePlacementInput>,
        scene: SeqNoteScene,
        measure: NoteMeasure = DefaultNoteMeasure,
        policy: SeqNotePolicy = SeqNotePolicy(),
        maxNotes: Int = 200,
        maxAnchors: Int = 8,
        maxNoteWidth: Float = dev.kuml.layout.bridge.NoteLayout.MAX_W,
    ): NotePlacementResult {
        val truncated = mutableListOf<String>()
        val unresolved = mutableListOf<UnresolvedAnchor>()
        val degraded = mutableListOf<String>()
        var crossings = 0
        if (notes.size > maxNotes) truncated += notes.drop(maxNotes).map { it.noteId }

        val resolved = LinkedHashMap<NotePlacementInput, List<Target>>()
        val loose = mutableListOf<NotePlacementInput>()
        for (note in notes.take(maxNotes)) {
            if (note.anchors.size > maxAnchors) truncated += note.noteId
            val targets = mutableListOf<Target>()
            for (ref in note.anchors.take(maxAnchors)) {
                val stem = scene.stems[ref.targetId]
                val msg = scene.messages[ref.targetId]
                when {
                    stem != null ->
                        targets +=
                            StemTarget(
                                linkId = ref.linkId,
                                id = ref.targetId,
                                stem = stem,
                                y = min(stem.top + policy.stemInset, stem.bottom),
                            )
                    msg != null && msg.isNotEmpty() -> targets += MessageTarget(linkId = ref.linkId, id = ref.targetId, pts = msg)
                    else -> unresolved += UnresolvedAnchor(noteId = note.noteId, anchorId = ref.targetId)
                }
            }
            if (targets.isEmpty()) loose += note else resolved[note] = targets
        }
        val ordered = resolved.entries.sortedBy { e -> e.value.minOf { it.y } }

        val content = contentBounds(scene)
        val nodes = LinkedHashMap(layout.nodes)
        val edges = LinkedHashMap(layout.edges)
        val placed = mutableListOf<Rect>()
        val routes = mutableListOf<List<Point>>()
        var extent = rectOf(left = 0f, top = 0f, right = layout.canvas.width, bottom = layout.canvas.height)
        layout.nodes.values.forEach { extent = extent.union(it.bounds) }

        fun commit(
            note: NotePlacementInput,
            targets: List<Target>,
            plan: Plan,
        ) {
            nodes[NodeId(note.noteId)] = NodeLayout(bounds = plan.rect)
            placed += plan.rect
            extent = extent.union(plan.rect)
            targets.forEachIndexed { i, t ->
                val pts = plan.routes[i]
                edges[EdgeId(t.linkId)] = routeOf(pts)
                routes += pts
                pts.forEach { extent = extent.union(rectOf(left = it.x, top = it.y, right = it.x, bottom = it.y)) }
            }
            crossings += plan.crossings
        }

        // Nacharbeit 68: notes on the same single anchor stay on one side. Anchor ids -> inner column kind (1 or 2).
        val stickyColumn = HashMap<Set<String>, Int>()
        for ((note, targets) in ordered) {
            val size = measure.measure(body = note.body, maxWidth = maxNoteWidth, kind = note.kind)
            val anchorKey = targets.map { targetId(it) }.toSet()
            val preferred = if (targets.size == 1) stickyColumn[anchorKey] ?: 0 else 0
            val ctx = Ctx(scene = scene, policy = policy, placed = placed, routes = routes, own = targets, preferredKind = preferred)
            val plan = choose(size = size, targets = targets, content = content, ctx = ctx)
            if (plan != null) {
                if (targets.size == 1 &&
                    (plan.columnKind == 1 || plan.columnKind == 2)
                ) {
                    if (anchorKey !in stickyColumn) stickyColumn[anchorKey] = plan.columnKind
                }
                commit(note = note, targets = targets, plan = plan)
            } else {
                degraded += note.noteId
                commit(note = note, targets = targets, plan = fallback(size = size, targets = targets, content = content, ctx = ctx))
            }
        }

        // Notes without a resolvable anchor: the right margin column, as high as possible.
        for (note in loose) {
            val size = measure.measure(body = note.body, maxWidth = maxNoteWidth, kind = note.kind)
            val left = content.right + policy.marginGap
            var top = content.top
            while (placed.any { it.inflated(policy.clearance).intersects(Rect(origin = Point(x = left, y = top), size = size)) }) {
                top += policy.step
            }
            val rect = Rect(origin = Point(x = left, y = top), size = size)
            nodes[NodeId(note.noteId)] = NodeLayout(bounds = rect)
            placed += rect
            extent = extent.union(rect)
        }

        val raw =
            layout.copy(
                canvas = Size(width = max(layout.canvas.width, extent.right), height = max(layout.canvas.height, extent.bottom)),
                nodes = nodes,
                edges = edges,
            )
        val dx = if (extent.left < 0f) -extent.left else 0f
        val dy = if (extent.top < 0f) -extent.top else 0f
        return NotePlacementResult(
            layout = raw.translated(dx = dx, dy = dy),
            unresolved = unresolved,
            truncated = truncated,
            dx = dx,
            dy = dy,
            degraded = degraded,
            crossings = crossings,
        )
    }

    // ---------------------------------------------------------------- candidate search

    private class Ctx(
        val scene: SeqNoteScene,
        val policy: SeqNotePolicy,
        val placed: List<Rect>,
        val routes: List<List<Point>>,
        val own: List<Target>,
        /** Inner column kind (1 or 2) earlier notes on the same single anchor went to, or 0. */
        val preferredKind: Int = 0,
    ) {
        val ownStems: Set<Float> = own.filterIsInstance<StemTarget>().map { it.stem.x }.toSet()
    }

    private fun contentBounds(scene: SeqNoteScene): Rect {
        var r: Rect? = null

        fun add(rect: Rect) {
            r = r?.union(rect) ?: rect
        }

        fun addPoint(p: Point) = add(rectOf(left = p.x, top = p.y, right = p.x, bottom = p.y))
        scene.stems.values.forEach {
            addPoint(Point(x = it.x, y = it.top))
            addPoint(Point(x = it.x, y = it.bottom))
        }
        scene.heads.values.forEach { add(it) }
        scene.blockers.forEach { add(it) }
        (scene.frameLines + scene.operandLines + scene.messageLines).forEach { l -> l.forEach { addPoint(it) } }
        return r ?: rectOf(left = 0f, top = 0f, right = 0f, bottom = 0f)
    }

    private fun choose(
        size: Size,
        targets: List<Target>,
        content: Rect,
        ctx: Ctx,
    ): Plan? {
        val policy = ctx.policy
        val topY = targets.minOf { it.y }
        val columns = columnsFor(size = size, targets = targets, content = content, ctx = ctx)
        val rows = ctx.scene.rowPitch.coerceAtLeast(policy.step)
        for (col in columns) {
            // the margin columns are free of drawing: they may search twice as far, among the stacked notes
            val maxShift = rows * policy.maxRowSteps * (if (col.kind >= 3 || col.kind == ctx.preferredKind) 2 else 1)
            var best: Plan? = null
            var bestShift = Float.MAX_VALUE
            var shift = 0f
            while (shift <= maxShift + 0.01f) {
                for (sign in if (shift == 0f) listOf(1f) else listOf(1f, -1f)) {
                    val dy = shift * sign
                    val rect = Rect(origin = Point(x = col.left, y = topY - size.height / 2f + dy), size = size)
                    if (!rectAdmissible(rect = rect, ctx = ctx)) continue
                    val plan = planRoutes(rect = rect, targets = targets, ctx = ctx) ?: continue
                    if (best == null || plan.crossings < best.crossings) {
                        best = plan
                        bestShift = shift
                    }
                }
                // the first admissible distance with the fewest crossings wins; a farther one only if it saves crossings
                if (best != null && best.crossings == 0) break
                shift += policy.step
            }
            if (best != null && bestShift <= maxShift) {
                best.columnKind = col.kind
                return best
            }
        }
        return null
    }

    private fun columnsFor(
        size: Size,
        targets: List<Target>,
        content: Rect,
        ctx: Ctx,
    ): List<Column> {
        val gap = ctx.policy.anchorSideGap
        val margin = ctx.policy.marginGap
        val xs = targets.map { anchorX(it) }
        val minX = targets.minOf { leftX(it) }
        val maxX = targets.maxOf { rightX(it) }
        val rightMargin = Column(kind = 3, left = content.right + margin, right = content.right + margin + size.width)
        val leftMargin = Column(kind = 4, left = content.left - margin - size.width, right = content.left - margin)
        if (targets.size > 1) {
            val nearLeft = (xs.min() - content.left) <= (content.right - xs.max())
            return if (nearLeft) listOf(leftMargin, rightMargin) else listOf(rightMargin, leftMargin)
        }
        val inner =
            listOf(
                Column(kind = 1, left = maxX + gap, right = maxX + gap + size.width),
                Column(kind = 2, left = minX - gap - size.width, right = minX - gap),
            )
        val sticky = inner.firstOrNull { it.kind == ctx.preferredKind }
        val orderedInner = if (sticky != null) listOf(sticky) + inner.filter { it !== sticky } else inner
        return orderedInner + listOf(rightMargin, leftMargin)
    }

    private fun targetId(t: Target): String =
        when (t) {
            is StemTarget -> t.id
            is MessageTarget -> t.id
        }

    private fun anchorX(t: Target): Float =
        when (t) {
            is StemTarget -> t.stem.x
            is MessageTarget -> t.mid.x
        }

    private fun leftX(t: Target): Float =
        when (t) {
            is StemTarget -> t.stem.x
            is MessageTarget -> t.pts.minOf { it.x }
        }

    private fun rightX(t: Target): Float =
        when (t) {
            is StemTarget -> t.stem.x
            is MessageTarget -> t.pts.maxOf { it.x }
        }

    /** Hard rule for the rectangle of a note. */
    private fun rectAdmissible(
        rect: Rect,
        ctx: Ctx,
    ): Boolean {
        val c = ctx.policy.clearance
        val zone = rect.inflated(c)
        val s = ctx.scene
        if (s.heads.values.any { it.inflated(c).intersects(rect) }) return false
        if (s.blockers.any { it.inflated(c).intersects(rect) }) return false
        for (st in s.stems.values) {
            if (st.x in ctx.ownStems) {
                // the own stem: the note keeps exactly the anchor gap, never covers it
                if (st.x > rect.left && st.x < rect.right && st.bottom > rect.top && st.top < rect.bottom) return false
                continue
            }
            if (st.x > zone.left && st.x < zone.right && st.bottom > zone.top && st.top < zone.bottom) return false
        }
        for (l in s.frameLines + s.operandLines + s.messageLines) {
            for (i in 0 until l.size - 1) if (segmentIntersectsRect(a = l[i], b = l[i + 1], r = zone)) return false
        }
        if (ctx.placed.any { it.inflated(c).intersects(rect) }) return false
        val lz = rect.inflated(ctx.policy.lineClearance)
        for (r in ctx.routes) {
            for (i in 0 until r.size - 1) if (segmentIntersectsRect(a = r[i], b = r[i + 1], r = lz)) return false
        }
        return true
    }

    private fun planRoutes(
        rect: Rect,
        targets: List<Target>,
        ctx: Ctx,
    ): Plan? {
        val mine = mutableListOf<List<Point>>()
        var crossings = 0
        for (t in targets) {
            val candidates =
                when (t) {
                    is StemTarget -> stemRoutes(rect = rect, t = t, ctx = ctx)
                    is MessageTarget -> messageRoutes(rect = rect, t = t, ctx = ctx)
                }
            var best: List<Point>? = null
            var bestCross = Int.MAX_VALUE
            for (route in candidates) {
                val cross = routeCrossings(route = route, ctx = ctx, rect = rect, mine = mine) ?: continue
                if (cross < bestCross) {
                    best = route
                    bestCross = cross
                    if (cross == 0) break
                }
            }
            if (best == null) return null
            mine += best
            crossings += bestCross
        }
        return Plan(rect = rect, routes = mine, crossings = crossings)
    }

    /** Horizontal lines from the note to the stem, nearest to the anchor height first. */
    private fun stemRoutes(
        rect: Rect,
        t: StemTarget,
        ctx: Ctx,
    ): List<List<Point>> {
        val lo = max(rect.top + 6f, t.stem.top + 6f)
        val hi = min(rect.bottom - 6f, t.stem.bottom - 6f)
        if (hi < lo) return emptyList()
        val fromX = if (rect.cx < t.stem.x) rect.right else rect.left
        val ys = ArrayList<Float>()
        var y = t.y.coerceIn(lo, hi)
        ys += y
        var d = 4f
        while (y - d >= lo || y + d <= hi) {
            if (y + d <= hi) ys += y + d
            if (y - d >= lo) ys += y - d
            d += 4f
        }
        return ys.map { yy -> listOf(Point(x = fromX, y = yy), Point(x = t.stem.x, y = yy)) }
    }

    /** A message anchor: corridor below the message, then up onto the message; or straight up from a note below it. */
    private fun messageRoutes(
        rect: Rect,
        t: MessageTarget,
        ctx: Ctx,
    ): List<List<Point>> {
        val mx = t.mid.x
        val my = t.mid.y
        val corridor = my + ctx.policy.corridor
        val out = ArrayList<List<Point>>()
        val insideX = mx > rect.left && mx < rect.right
        if (insideX) {
            // the note sits below the message: one vertical line from the note's top edge up to the message
            if (rect.top > my + 4f && mx >= rect.left + 10f && mx <= rect.right - 10f) {
                out += listOf(listOf(Point(x = mx, y = rect.top), Point(x = mx, y = my)))
            }
            return out
        }
        val fromX = if (rect.cx < mx) rect.right else rect.left
        val lo = rect.top + 6f
        val hi = rect.bottom - 6f
        if (hi < lo || hi < corridor) return out
        val yc = max(corridor, lo)
        out +=
            listOf(
                if (yc ==
                    my
                ) {
                    listOf(Point(x = fromX, y = yc), Point(x = mx, y = my))
                } else {
                    mutableRoute(fromX = fromX, yc = yc, mx = mx, my = my)
                },
            )
        return out
    }

    private fun mutableRoute(
        fromX: Float,
        yc: Float,
        mx: Float,
        my: Float,
    ): List<Point> = listOf(Point(x = fromX, y = yc), Point(x = mx, y = yc), Point(x = mx, y = my))

    /**
     * Number of tolerated crossings of [route], or null if it violates a hard rule: it may not touch a foreign note,
     * head, solid block, operand separator, nor run along a message, a frame edge or a stem.
     */
    private fun routeCrossings(
        route: List<Point>,
        ctx: Ctx,
        rect: Rect,
        mine: List<List<Point>>,
    ): Int? {
        val s = ctx.scene
        val tol = ctx.policy.parallelTolerance
        var cross = 0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            if (s.heads.values.any { segmentIntersectsRect(a = a, b = b, r = it.inflated(2f)) }) return null
            if (s.blockers.any { segmentIntersectsRect(a = a, b = b, r = it.inflated(2f)) }) return null
            if (ctx.placed.any { segmentIntersectsRect(a = a, b = b, r = it.inflated(2f)) }) return null
            for (l in s.operandLines) {
                for (j in 0 until l.size - 1) {
                    if (segmentsCross(a = a, b = b, c = l[j], d = l[j + 1]) ||
                        parallelNear(a = a, b = b, c = l[j], d = l[j + 1], tol = tol)
                    ) {
                        return null
                    }
                }
            }
            for (l in s.frameLines) {
                for (j in 0 until l.size - 1) {
                    if (parallelNear(a = a, b = b, c = l[j], d = l[j + 1], tol = tol)) return null
                    if (segmentsCross(a = a, b = b, c = l[j], d = l[j + 1])) cross++
                }
            }
            for (l in s.messageLines) {
                for (j in 0 until l.size - 1) {
                    if (parallelNear(a = a, b = b, c = l[j], d = l[j + 1], tol = tol)) return null
                    if (segmentsCross(a = a, b = b, c = l[j], d = l[j + 1])) cross++
                }
            }
            for (st in s.stems.values) {
                val c = Point(x = st.x, y = st.top)
                val d = Point(x = st.x, y = st.bottom)
                if (st.x in ctx.ownStems) continue
                if (parallelNear(a = a, b = b, c = c, d = d, tol = tol)) return null
                if (segmentsCross(a = a, b = b, c = c, d = d)) cross++
            }
            for (r in ctx.routes + mine) {
                for (j in 0 until r.size - 1) {
                    if (parallelNear(a = a, b = b, c = r[j], d = r[j + 1], tol = tol)) return null
                    if (segmentsCross(a = a, b = b, c = r[j], d = r[j + 1])) cross++
                }
            }
        }
        return cross
    }

    /** True if two axis-parallel segments lie on (nearly) the same line and overlap along it. */
    private fun parallelNear(
        a: Point,
        b: Point,
        c: Point,
        d: Point,
        tol: Float,
    ): Boolean {
        val abH = abs(a.y - b.y) < 0.01f
        val abV = abs(a.x - b.x) < 0.01f
        val cdH = abs(c.y - d.y) < 0.01f
        val cdV = abs(c.x - d.x) < 0.01f
        if (abH && cdH) {
            return abs(a.y - c.y) <= tol && min(max(a.x, b.x), max(c.x, d.x)) - max(min(a.x, b.x), min(c.x, d.x)) > 0.5f
        }
        if (abV && cdV) {
            return abs(a.x - c.x) <= tol && min(max(a.y, b.y), max(c.y, d.y)) - max(min(a.y, b.y), min(c.y, d.y)) > 0.5f
        }
        return false
    }

    /**
     * Last resort: the right margin column below everything placed so far, free by construction. Its anchor lines run
     * through a lane of the margin (one vertical leg), so the note rectangle still overlaps nothing.
     */
    private fun fallback(
        size: Size,
        targets: List<Target>,
        content: Rect,
        ctx: Ctx,
    ): Plan {
        val policy = ctx.policy
        val left = content.right + policy.marginGap
        var top = max(content.top, targets.minOf { it.y } - size.height / 2f)
        while (ctx.placed.any { it.inflated(policy.clearance).intersects(Rect(origin = Point(x = left, y = top), size = size)) }) {
            top += policy.step
        }
        val rect = Rect(origin = Point(x = left, y = top), size = size)
        val laneX = content.right + policy.marginGap / 2f
        val routes =
            targets.mapIndexed { i, t ->
                val ay = if (t is MessageTarget) t.mid.y + policy.corridor else t.y
                val ax = if (t is MessageTarget) t.mid.x else anchorX(t)
                val ny = (rect.top + 8f + i * 8f).coerceAtMost(rect.bottom - 6f)
                listOf(
                    Point(x = rect.left, y = ny),
                    Point(x = laneX + i * 4f, y = ny),
                    Point(x = laneX + i * 4f, y = ay),
                    Point(x = ax, y = ay),
                )
            }
        return Plan(rect = rect, routes = routes, crossings = routes.size)
    }

    private fun routeOf(pts: List<Point>): EdgeRoute =
        if (pts.size == 2) {
            EdgeRoute.Direct(source = pts.first(), target = pts.last())
        } else {
            EdgeRoute.OrthogonalRounded(
                source = pts.first(),
                target = pts.last(),
                waypoints = pts.subList(1, pts.size - 1),
                cornerRadiusPx = 0f,
            )
        }
}
