package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.ReqNoteRoom
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import dev.kuml.layout.polylinePoints
import dev.kuml.sysml2.ReqDiagram
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

    /**
     * Places the notes of [diagram].
     *
     * @param extras anchor geometries only the caller knows (ports, owner frames, lifeline stems); they win over the layout.
     * @param edgeLabels the label text lines of an edge id (empty = no label); used for the label-rectangle estimate.
     * @param nodeObstacle the rectangle a node blocks (a sequence lifeline node is head plus stem, but only the head blocks).
     * @param placedLabels edge labels already planned (edge id to rectangle, layout coordinates): they are obstacles as
     *   drawn; the estimate of [edgeLabels] is only used for edges without an entry.
     * @param measure sizes the note boxes (state machines wrap at 220 px, see [WidthLimitedNoteMeasure]).
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
        placedLabels: Map<String, Rect> = emptyMap(),
        measure: NoteMeasure = DefaultNoteMeasure,
    ): NotePlacementResult {
        val placed =
            placeNotes(
                model = model,
                diagram = diagram,
                layout = layout,
                extras = extras,
                edgeLabels = edgeLabels,
                configure = configure,
                config = config,
                nodeObstacle = nodeObstacle,
                placedLabels = placedLabels,
                measure = measure,
            )
        return withUndrawn(model = model, diagram = diagram, placed = placed)
    }

    /**
     * Adds the `KUML-NOTE-W002` check to [placed]: declared minus drawn, computed against the unfiltered model, and
     * reported to the surrounding [NoteUndrawnCollector] scope.
     */
    public fun withUndrawn(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        placed: NotePlacementResult,
    ): NotePlacementResult {
        val drawn =
            placed.layout.nodes.keys
                .map { it.value }
                .toSet()
        val undrawn = Sysml2NoteDrawCheck.undrawn(model = model, diagram = diagram, drawnNoteIds = drawn)
        NoteUndrawnCollector.report(undrawn)
        return if (undrawn.isEmpty()) placed else placed.copy(undrawn = undrawn)
    }

    private fun placeNotes(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        layout: LayoutResult,
        extras: Map<String, AnchorGeometry>,
        edgeLabels: (String) -> List<String>,
        configure: (ObstacleIndex) -> Unit,
        config: NotePlacementConfig,
        nodeObstacle: (String, Rect) -> Rect,
        placedLabels: Map<String, Rect>,
        measure: NoteMeasure,
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
                        kind = c.note.kind,
                        anchors =
                            model.commentLinks
                                .filter { it.link.noteId == c.id }
                                .map { NoteAnchorRef(linkId = it.id, targetId = it.link.targetId) },
                    )
                }
        if (inputs.isEmpty()) return NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val obstacles = obstaclesOf(layout = layout, edgeLabels = edgeLabels, nodeObstacle = nodeObstacle, placedLabels = placedLabels)
        configure(obstacles)
        // Nacharbeit 55: requirement-group frames keep notes off their lines; notes owned by one group go to its strip (the area, see NotePlacementConfig.noteHomes).
        val homes =
            if (diagram is ReqDiagram) {
                reqGroupRoom(
                    model = model,
                    diagram = diagram,
                    layout = layout,
                    obstacles = obstacles,
                )
            } else {
                emptyMap()
            }
        return NotePlacer.place(
            layout = layout,
            notes = inputs,
            resolver = resolver,
            obstacles = obstacles,
            measure = measure,
            config = if (homes.isEmpty()) config else config.copy(noteHomes = homes),
        )
    }

    /**
     * Requirement diagrams with groups (Nacharbeit 55): adds a hard [ObstacleKind.KEEP_OUT] band of
     * [ReqNoteRoom.BAND_PX] on each side of every group frame line and returns the slots of the notes a single group
     * owns (inside that group's frame, in the strip the layout bridge reserved).
     */
    private fun reqGroupRoom(
        model: Sysml2Model,
        diagram: ReqDiagram,
        layout: LayoutResult,
        obstacles: ObstacleIndex,
    ): Map<String, Rect> {
        if (layout.groups.isEmpty()) return emptyMap()
        val b = ReqNoteRoom.BAND_PX
        for ((id, g) in layout.groups) {
            val f = g.bounds
            val x = f.origin.x
            val y = f.origin.y
            val w = f.size.width
            val h = f.size.height
            val strips =
                listOf(
                    Rect(origin = Point(x = x - b, y = y - b), size = Size(width = w + 2f * b, height = 2f * b)),
                    Rect(origin = Point(x = x - b, y = y + h - b), size = Size(width = w + 2f * b, height = 2f * b)),
                    Rect(origin = Point(x = x - b, y = y - b), size = Size(width = 2f * b, height = h + 2f * b)),
                    Rect(origin = Point(x = x + w - b, y = y - b), size = Size(width = 2f * b, height = h + 2f * b)),
                )
            strips.forEach { obstacles.addRect(kind = ObstacleKind.KEEP_OUT, r = it, ownerId = id.value) }
        }
        val visibleIds =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        val membership =
            ReqNoteRoom.membership(
                diagram = diagram,
                liveGroupIds =
                    layout.groups.keys
                        .map { it.value }
                        .toSet(),
                visibleNodeIds = visibleIds,
            )
        val owned =
            ReqNoteRoom.ownedNotes(model = model, diagram = diagram, groupOfNode = membership.groupOfNode, visibleNodeIds = visibleIds)
        val out = LinkedHashMap<String, Rect>()
        for ((gid, notes) in owned) {
            val frame = layout.groups[gid]?.bounds ?: continue
            val home = ReqNoteRoom.home(frame = frame, notes = notes, padding = Sysml2LayoutBridge.REQ_GROUP_PADDING) ?: continue
            notes.forEach { (noteId, _) -> out[noteId] = home }
        }
        return out
    }

    /** The obstacle model of an existing layout. */
    public fun obstaclesOf(
        layout: LayoutResult,
        edgeLabels: (String) -> List<String> = { emptyList() },
        nodeObstacle: (String, Rect) -> Rect = { _, r -> r },
        placedLabels: Map<String, Rect> = emptyMap(),
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
            val planned = placedLabels[id.value]
            val lines = if (planned == null) edgeLabels(id.value) else emptyList()
            if (planned != null) o.addRect(kind = ObstacleKind.EDGE_LABEL, r = planned, ownerId = id.value)
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
        val size = Sysml2LabelSizing.sizeOf(lines)
        return Rect(origin = Point(x = mid.x - size.width / 2f, y = mid.y - size.height / 2f), size = size)
    }
}
