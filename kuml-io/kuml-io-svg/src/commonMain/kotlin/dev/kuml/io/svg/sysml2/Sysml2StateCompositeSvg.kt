package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.sysml2.edge.StmObstacleKind
import dev.kuml.io.svg.sysml2.edge.StmObstacleNode
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StateRegion
import dev.kuml.sysml2.Sysml2Model

/** Height of the name header strip of a composite state frame (px). */
internal const val STM_COMPOSITE_HEADER_HEIGHT: Float = 28f

/**
 * Draws the frames of all composite states (wave A1) that the layout bridge turned into
 * compound groups: a rounded rectangle with a bold name header, optional `entry / exit / do`
 * lines under a hairline, and — for orthogonal regions — dashed separators plus italic region
 * names. Frames are painted outermost first so nested frames and their children layer on top.
 *
 * Which orientation the separators take is read off the layout result (regions stacked
 * vertically get horizontal separators, regions side by side get vertical ones), so the renderer
 * does not depend on the layout direction. Separators always span the full width/height of the
 * composite, which is what keeps the regions visually equally wide.
 *
 * All text goes through the builder's escaping; ids go through [xmlEscapeAttr].
 */
internal fun renderCompositeStateFrames(
    model: Sysml2Model,
    visibleIds: Set<String>,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    val composites =
        model.definitions
            .filterIsInstance<StateDefinition>()
            .filter { it.id in visibleIds && it.effectiveKind == StateKind.State }
            .mapNotNull { def -> layoutResult.groups[GroupId(def.id)]?.let { def to it.bounds } }
            .sortedByDescending { (_, b) -> b.size.width * b.size.height }
    for ((def, raw) in composites) {
        val b = Rect(origin = raw.origin.copy(x = raw.origin.x + paddingPx, y = raw.origin.y + paddingPx), size = raw.size)
        val x = b.origin.x
        val y = b.origin.y
        val w = b.size.width
        val h = b.size.height
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(def.id), "class" to "kuml-composite-state")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(x),
                        "y" to fmt3(y),
                        "width" to fmt3(w),
                        "height" to fmt3(h),
                        "rx" to "10",
                        "ry" to "10",
                        "class" to "kuml-class",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to fmt3(x + w / 2f),
                        "y" to fmt3(y + 18f),
                        "text-anchor" to "middle",
                        "font-size" to "13",
                        "font-weight" to "bold",
                    ),
            ) { text(def.name) }
            val actions =
                listOfNotNull(
                    def.entryAction?.takeIf { it.isNotEmpty() }?.let { "entry / $it" },
                    def.exitAction?.takeIf { it.isNotEmpty() }?.let { "exit / $it" },
                    def.doAction?.takeIf { it.isNotEmpty() }?.let { "do / $it" },
                )
            val headerBottom = y + STM_COMPOSITE_HEADER_HEIGHT
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to fmt3(x),
                        "y1" to fmt3(headerBottom),
                        "x2" to fmt3(x + w),
                        "y2" to fmt3(headerBottom),
                        "class" to "kuml-divider",
                        "stroke-width" to "1",
                    ),
            )
            actions.forEachIndexed { i, line ->
                tag(
                    name = "text",
                    attrs = mapOf("class" to "kuml-body", "x" to fmt3(x + 8f), "y" to fmt3(headerBottom + 13f + i * 13f)),
                ) { text(line) }
            }

            val regions: List<Pair<StateRegion, Rect>> =
                def.regions.mapNotNull { r ->
                    layoutResult.groups[GroupId(r.id)]?.let { g ->
                        r to
                            Rect(origin = Point(x = g.bounds.origin.x + paddingPx, y = g.bounds.origin.y + paddingPx), size = g.bounds.size)
                    }
                }
            for ((region, rect) in regions) {
                region.name?.takeIf { it.isNotEmpty() }?.let { name ->
                    tag(
                        name = "text",
                        attrs =
                            mapOf(
                                "class" to "kuml-body",
                                "x" to fmt3(rect.origin.x + 8f),
                                "y" to fmt3(rect.origin.y + 13f),
                                "text-anchor" to "start",
                                "font-size" to "11",
                                "font-style" to "italic",
                            ),
                    ) { text(name) }
                }
            }
            for ((a, c) in compositeSeparators(frame = b, headerBottom = headerBottom, regionRects = regions.map { it.second })) {
                separator(x1 = a.x, y1 = a.y, x2 = c.x, y2 = c.y)
            }
        }
    }
}

/**
 * Dashed separator lines between the orthogonal regions of a composite whose frame is [frame].
 * Regions stacked vertically get horizontal separators spanning the full width, regions side by
 * side get vertical ones from [headerBottom] to the bottom. Shared by the drawing code and the
 * label placer so both see the same geometry.
 */
internal fun compositeSeparators(
    frame: Rect,
    headerBottom: Float,
    regionRects: List<Rect>,
): List<Pair<Point, Point>> {
    if (regionRects.size < 2) return emptyList()
    val x = frame.origin.x
    val y = frame.origin.y
    val w = frame.size.width
    val h = frame.size.height
    val byY = regionRects.sortedBy { it.origin.y }
    val stacked = byY.zipWithNext().all { (a, c) -> a.origin.y + a.size.height <= c.origin.y + 0.5f }
    return if (stacked) {
        byY.zipWithNext().map { (a, c) ->
            val sy = ((a.origin.y + a.size.height) + c.origin.y) / 2f
            Point(x = x, y = sy) to Point(x = x + w, y = sy)
        }
    } else {
        regionRects.sortedBy { it.origin.x }.zipWithNext().map { (a, c) ->
            val sx = ((a.origin.x + a.size.width) + c.origin.x) / 2f
            Point(x = sx, y = headerBottom) to Point(x = sx, y = y + h)
        }
    }
}

/**
 * Obstacles for the STM label placer in final canvas coordinates ([paddingPx] applied): every
 * plain node as [StmObstacleKind.Simple], every composite group with its header, region names
 * and separators, and every region group.
 */
internal fun stmObstacleNodes(
    model: Sysml2Model,
    visibleIds: Set<String>,
    layoutResult: LayoutResult,
    paddingPx: Float,
): List<StmObstacleNode> {
    fun shifted(r: Rect) = Rect(origin = Point(x = r.origin.x + paddingPx, y = r.origin.y + paddingPx), size = r.size)
    val out = ArrayList<StmObstacleNode>()
    val states = model.definitions.filterIsInstance<StateDefinition>().filter { it.id in visibleIds }
    val compositeIds =
        states
            .filter { it.effectiveKind == StateKind.State }
            .map { it.id }
            .filter {
                GroupId(
                    it,
                ) in layoutResult.groups
            }.toSet()
    for ((id, node) in layoutResult.nodes) {
        if (id.value in compositeIds) continue
        out += StmObstacleNode(id = id.value, kind = StmObstacleKind.Simple, rect = shifted(node.bounds))
    }
    for (def in states) {
        if (def.id !in compositeIds) continue
        val frame = shifted(layoutResult.groups.getValue(GroupId(def.id)).bounds)
        val headerBottom = frame.origin.y + STM_COMPOSITE_HEADER_HEIGHT
        val regions =
            def.regions.mapNotNull { r ->
                layoutResult.groups[GroupId(r.id)]?.let { g -> r to shifted(g.bounds) }
            }
        val names =
            regions.mapNotNull { (r, rect) ->
                r.name?.takeIf { it.isNotEmpty() }?.let { n ->
                    // One 11 px italic line at (x + 8, y + 13); width estimated with the placer's character width.
                    Rect(
                        origin = Point(x = rect.origin.x + 8f, y = rect.origin.y + 2f),
                        size = dev.kuml.layout.Size(width = n.length * 7.2f * 11f / 12f, height = 14f),
                    )
                }
            }
        out +=
            StmObstacleNode(
                id = def.id,
                kind = StmObstacleKind.Composite,
                rect = frame,
                headerBottom = headerBottom,
                regionNameRects = names,
                dividers = compositeSeparators(frame = frame, headerBottom = headerBottom, regionRects = regions.map { it.second }),
            )
        for ((r, rect) in regions) out += StmObstacleNode(id = r.id, kind = StmObstacleKind.Region, rect = rect)
    }
    return out
}

private fun SvgBuilder.separator(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
) {
    tag(
        name = "line",
        attrs =
            mapOf(
                "x1" to fmt3(x1),
                "y1" to fmt3(y1),
                "x2" to fmt3(x2),
                "y2" to fmt3(y2),
                "class" to "kuml-divider",
                "stroke-dasharray" to "6 4",
                "stroke-width" to "1",
            ),
    )
}
