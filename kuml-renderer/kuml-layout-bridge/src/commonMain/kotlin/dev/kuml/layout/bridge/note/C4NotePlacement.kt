package dev.kuml.layout.bridge.note

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Note placement for C4 diagrams. A note is shown when the diagram lists it in `noteIds` or when one of its
 * anchors (an element id, i.e. a layout node or group, or a relationship id, i.e. a layout edge) is visible.
 * Before this existed `note(...)` in the C4 DSL was a no-op. The `placement` hint of a note is evaluated as a soft
 * hint; when no position on the requested side obeys the layout rules the default placement is used and
 * `KUML-NOTE-W004` is reported.
 */
public object C4NotePlacement {
    /**
     * PAL Welle X7: height of the strip under the top edge of a system boundary frame that stays free of notes inside the frame:
     * the two title lines (baselines at y = 14 and y = 30) need 34 px of ink, plus a gap of [C4_FRAME_NOTE_GAP_PX]. The title
     * obstacle of the shared note placement (`Sysml2NotePlacement.COMPOSITE_TITLE_HEIGHT`) is only 30 px.
     */
    public const val C4_FRAME_TITLE_KEEP_OUT_PX: Float = 46f

    /** PAL Welle X7: cap on the positions tried inside a frame (step 6 px; the shared cap of 600 stops after the first 84 px of a wide frame). */
    internal const val C4_FRAME_HOME_MAX_CANDIDATES: Int = 30_000

    /** PAL Welle X7: clear distance of a note inside a system boundary frame to the frame lines. */
    public const val C4_FRAME_NOTE_GAP_PX: Float = 12f

    /** Places the notes of [model] that [diagram] shows; returns the layout unchanged when there are none. */
    public fun place(
        model: C4Model,
        diagram: C4Diagram,
        layout: LayoutResult,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        var placed = placeNotes(model = model, diagram = diagram, layout = layout, config = config)
        if (placed.hintNotHonoured.isNotEmpty()) {
            val lines = model.notes.associate { it.id to it.sourceLine }
            val misses = placed.hintNotHonoured.map { it.copy(sourceLine = lines[it.noteId]) }
            NotePlacementMissCollector.report(misses)
            placed = placed.copy(hintNotHonoured = misses)
        }
        val drawn =
            placed.layout.nodes.keys
                .map { it.value }
                .toSet()
        val undrawn = undrawnNotes(model = model, diagram = diagram, drawnNoteIds = drawn)
        NoteUndrawnCollector.report(undrawn)
        return if (undrawn.isEmpty()) placed else placed.copy(undrawn = undrawn)
    }

    /**
     * Notes [diagram] declares (lists in `noteIds`, or anchors in its elements and relationships) that are not in
     * [drawnNoteIds]. Notes with only unknown anchors are `KUML-NOTE-W001`, not W002.
     */
    public fun undrawnNotes(
        model: C4Model,
        diagram: C4Diagram,
        drawnNoteIds: Set<String>,
    ): List<UndrawnNote> {
        if (model.notes.isEmpty()) return emptyList()
        val known = model.elements.map { it.id }.toSet() + model.relationships.map { it.id }
        val explicit = diagram.noteIds.toSet()
        val scope = diagram.elements.toSet() + diagram.relationships
        val out = mutableListOf<UndrawnNote>()
        for (n in model.notes) {
            if (n.id in drawnNoteIds) continue
            val anchors =
                model.noteLinks
                    .filter { it.noteId == n.id }
                    .map { it.targetId }
                    .filter { it in known }
            if (anchors.isEmpty()) continue
            if (n.id !in explicit && anchors.none { it in scope }) continue
            val anchor = anchors.firstOrNull { it in scope } ?: anchors.first()
            out +=
                UndrawnNote(
                    noteId = n.id,
                    anchorId = anchor,
                    reason = dev.kuml.core.model.note.KumlNoteCodes.NoteNotDrawnReason.OTHER_LEVEL,
                    sourceLine = n.sourceLine,
                )
        }
        return out
    }

    private fun placeNotes(
        model: C4Model,
        diagram: C4Diagram,
        layout: LayoutResult,
        config: NotePlacementConfig,
    ): NotePlacementResult {
        val none = NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        if (model.notes.isEmpty()) return none
        val resolver = AnchorGeometryResolver(layout = layout)
        val explicit = diagram.noteIds.toSet()
        val taken =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        val inputs =
            model.notes
                .filter { n ->
                    n.id !in taken &&
                        (n.id in explicit || model.noteLinks.any { it.noteId == n.id && resolver.canResolve(it.targetId) })
                }.map { n ->
                    NotePlacementInput(
                        noteId = n.id,
                        body = n.body,
                        kind = n.kind,
                        placement = n.placement,
                        anchors =
                            model.noteLinks.filter { it.noteId == n.id }.map {
                                NoteAnchorRef(
                                    linkId = it.id,
                                    targetId = it.targetId,
                                )
                            },
                    )
                }
        if (inputs.isEmpty()) return none
        val labels = model.relationships.associate { it.id to listOfNotNull(it.label.takeIf { l -> l.isNotEmpty() }) }
        val obstacles = Sysml2NotePlacement.obstaclesOf(layout = layout, edgeLabels = { labels[it].orEmpty() })
        frameKeepOut(layout = layout, obstacles = obstacles)
        val homes = frameHomes(model = model, inputs = inputs, layout = layout, resolver = resolver)
        return NotePlacer.place(
            layout = layout,
            notes = inputs,
            resolver = resolver,
            obstacles = obstacles,
            config =
                config.let {
                    if (homes.isEmpty()) {
                        it
                    } else {
                        it.copy(
                            noteHomes = homes,
                            noteHomeClearance = C4_FRAME_NOTE_GAP_PX,
                            noteHomeMaxCandidates = C4_FRAME_HOME_MAX_CANDIDATES,
                            edgeClearance = C4_FRAME_NOTE_GAP_PX,
                        )
                    }
                },
        )
    }

    /**
     * PAL Welle X7: no note may lie on a system boundary frame line. Adds hard [ObstacleKind.KEEP_OUT] strips of
     * [C4_FRAME_NOTE_GAP_PX] on both sides of the left, right and bottom frame line, and a strip over the top line that also
     * covers the title area ([C4_FRAME_TITLE_KEEP_OUT_PX] below the top edge).
     */
    private fun frameKeepOut(
        layout: LayoutResult,
        obstacles: ObstacleIndex,
    ) {
        val g = C4_FRAME_NOTE_GAP_PX
        for ((id, group) in layout.groups) {
            val f = group.bounds
            val x = f.origin.x
            val y = f.origin.y
            val w = f.size.width
            val h = f.size.height
            val strips =
                listOf(
                    Rect(origin = Point(x = x - g, y = y - g), size = Size(width = w + 2f * g, height = g + C4_FRAME_TITLE_KEEP_OUT_PX)),
                    Rect(origin = Point(x = x - g, y = y + h - g), size = Size(width = w + 2f * g, height = 2f * g)),
                    Rect(origin = Point(x = x - g, y = y - g), size = Size(width = 2f * g, height = h + 2f * g)),
                    Rect(origin = Point(x = x + w - g, y = y - g), size = Size(width = 2f * g, height = h + 2f * g)),
                )
            strips.forEach { obstacles.addRect(kind = ObstacleKind.KEEP_OUT, r = it, ownerId = id.value) }
        }
    }

    /**
     * PAL Welle X7: the area inside a system boundary frame for every note whose anchors are all members of that frame (a node
     * that lies inside the frame). A note anchored to the boundary itself, to an element outside the frame, to a relationship or
     * with mixed anchors has no area and stays outside as before. The area starts [C4_FRAME_NOTE_GAP_PX] inside the frame, its top
     * edge is [C4_FRAME_TITLE_KEEP_OUT_PX] - 12 px (the shared home search adds 12 px) below the frame top, so the first slot
     * lies below the title ink plus the gap.
     */
    private fun frameHomes(
        model: C4Model,
        inputs: List<NotePlacementInput>,
        layout: LayoutResult,
        resolver: AnchorGeometryResolver,
    ): Map<String, Rect> {
        if (layout.groups.isEmpty()) return emptyMap()
        val g = C4_FRAME_NOTE_GAP_PX
        val top = C4_FRAME_TITLE_KEEP_OUT_PX - 12f
        val out = LinkedHashMap<String, Rect>()
        for (n in inputs) {
            val targets =
                model.noteLinks
                    .filter { it.noteId == n.noteId }
                    .map { it.targetId }
                    .filter { resolver.canResolve(it) }
            if (targets.isEmpty()) continue
            val frame =
                layout.groups.values.map { it.bounds }.firstOrNull { f ->
                    targets.all { t ->
                        val b = layout.nodes[NodeId(t)]?.bounds ?: return@all false
                        b.left >= f.left && b.right <= f.right && b.top >= f.top && b.bottom <= f.bottom
                    }
                } ?: continue
            val w = frame.size.width - 2f * g
            val h = frame.size.height - top - g
            if (w <= 0f || h <= 0f) continue
            out[n.noteId] = Rect(origin = Point(x = frame.left + g, y = frame.top + top), size = Size(width = w, height = h))
        }
        return out
    }

    /** Warnings (`KUML-NOTE-W001`) for note anchors of [model] that name no element or relationship. */
    public fun unknownAnchors(model: C4Model): List<Pair<String, String>> {
        val known = model.elements.map { it.id }.toSet() + model.relationships.map { it.id }
        return model.noteLinks.filter { it.targetId !in known }.map { it.noteId to it.targetId }
    }
}
