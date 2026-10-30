package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model

/**
 * Glue between a SysML 2 model and the generic [NotePlacer]: decides which notes a diagram shows, feeds the
 * obstacle model (nodes, groups, composite titles, edges, estimated label rectangles) and returns the layout with
 * the notes and anchor lines added. Replaces the former ELK note nodes: ELK knows nothing about edge labels,
 * port labels or composite titles, so notes are placed after the layout.
 */
public object Sysml2NotePlacement {
    /** Height of the title strip of a group that counts as obstacle (title and entry text of a composite). */
    public const val COMPOSITE_TITLE_HEIGHT: Float = 30f

    /** Estimated width of one label character (same estimate as the renderer's label placement). */
    private const val LABEL_CHAR_PX: Float = 6.6f
    private const val LABEL_LINE_PX: Float = 15f
    private const val LABEL_PAD_PX: Float = 8f

    /**
     * Places the notes of [diagram].
     *
     * @param extras anchor geometries only the caller knows (ports, owner frames, lifeline stems); they win over the layout.
     * @param edgeLabels the label text lines of an edge id (empty = no label); used for the label-rectangle estimate.
     * @param nodeObstacle the rectangle a node blocks (a sequence lifeline node is head plus stem, but only the head blocks).
     * @param configure adds more obstacles (lifeline stems, fragment frames, message arrows, port labels).
     */
    public fun place(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        layout: LayoutResult,
        extras: Map<String, AnchorGeometry> = emptyMap(),
        edgeLabels: (String) -> List<String> = { emptyList() },
        configure: (ObstacleIndex) -> Unit = {},
        config: NotePlacementConfig = NotePlacementConfig(),
        nodeObstacle: (String, Rect) -> Rect = { _, r -> r },
    ): NotePlacementResult {
        if (model.comments.isEmpty()) return NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val resolver = AnchorGeometryResolver(layout = layout, extras = extras)
        val taken =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        val visible = model.notesVisibleIn(diagram = diagram, isAnchorVisible = { resolver.canResolve(it) })
        val inputs =
            visible.comments
                .filter { it.id !in taken }
                .map { c ->
                    NotePlacementInput(
                        noteId = c.id,
                        body = c.note.body,
                        anchors =
                            model.commentLinks
                                .filter { it.link.noteId == c.id }
                                .map { NoteAnchorRef(linkId = it.id, targetId = it.link.targetId) },
                    )
                }
        if (inputs.isEmpty()) return NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val obstacles = obstaclesOf(layout = layout, edgeLabels = edgeLabels, nodeObstacle = nodeObstacle)
        configure(obstacles)
        return NotePlacer.place(layout = layout, notes = inputs, resolver = resolver, obstacles = obstacles, config = config)
    }

    /** The obstacle model of an existing layout. */
    public fun obstaclesOf(
        layout: LayoutResult,
        edgeLabels: (String) -> List<String> = { emptyList() },
        nodeObstacle: (String, Rect) -> Rect = { _, r -> r },
    ): ObstacleIndex {
        val o = ObstacleIndex()
        for ((id, n) in layout.nodes) {
            o.addRect(kind = ObstacleKind.NODE, r = nodeObstacle(id.value, n.bounds), ownerId = id.value)
            for ((key, r) in n.labels) if (key.startsWith("port:")) o.addRect(kind = ObstacleKind.PORT_LABEL, r = r, ownerId = id.value)
        }
        for ((id, g) in layout.groups) {
            val b = g.bounds
            o.addRect(
                kind = ObstacleKind.COMPOSITE_TITLE,
                r = Rect(origin = b.origin, size = Size(width = b.size.width, height = minOf(COMPOSITE_TITLE_HEIGHT, b.size.height))),
                ownerId = id.value,
            )
            val r = b.origin
            val pts =
                listOf(
                    r,
                    Point(x = r.x + b.size.width, y = r.y),
                    Point(x = r.x + b.size.width, y = r.y + b.size.height),
                    Point(x = r.x, y = r.y + b.size.height),
                    r,
                )
            o.addPolyline(kind = ObstacleKind.FRAGMENT, pts = pts, ownerId = id.value)
        }
        for ((id, e) in layout.edges) {
            val pts = e.polylinePoints()
            o.addPolyline(kind = ObstacleKind.EDGE, pts = pts, ownerId = id.value)
            val lines = edgeLabels(id.value)
            if (lines.isNotEmpty() &&
                pts.size >= 2
            ) {
                o.addRect(kind = ObstacleKind.EDGE_LABEL, r = labelRect(pts = pts, lines = lines), ownerId = id.value)
            }
        }
        return o
    }

    private fun labelRect(
        pts: List<Point>,
        lines: List<String>,
    ): Rect {
        val mid = polylineMidpoint(pts)
        val w = (lines.maxOf { it.length } * LABEL_CHAR_PX + LABEL_PAD_PX)
        val h = lines.size * LABEL_LINE_PX + LABEL_PAD_PX / 2f
        return Rect(origin = Point(x = mid.x - w / 2f, y = mid.y - h / 2f), size = Size(width = w, height = h))
    }
}
