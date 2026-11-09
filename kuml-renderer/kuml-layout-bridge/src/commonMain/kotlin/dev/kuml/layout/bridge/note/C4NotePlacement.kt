package dev.kuml.layout.bridge.note

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.layout.LayoutResult

/**
 * Note placement for C4 diagrams. A note is shown when the diagram lists it in `noteIds` or when one of its
 * anchors (an element id, i.e. a layout node or group, or a relationship id, i.e. a layout edge) is visible.
 * Before this existed `note(...)` in the C4 DSL was a no-op.
 */
public object C4NotePlacement {
    /** Places the notes of [model] that [diagram] shows; returns the layout unchanged when there are none. */
    public fun place(
        model: C4Model,
        diagram: C4Diagram,
        layout: LayoutResult,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val placed = placeNotes(model = model, diagram = diagram, layout = layout, config = config)
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
        return NotePlacer.place(layout = layout, notes = inputs, resolver = resolver, obstacles = obstacles, config = config)
    }

    /** Warnings (`KUML-NOTE-W001`) for note anchors of [model] that name no element or relationship. */
    public fun unknownAnchors(model: C4Model): List<Pair<String, String>> {
        val known = model.elements.map { it.id }.toSet() + model.relationships.map { it.id }
        return model.noteLinks.filter { it.targetId !in known }.map { it.noteId to it.targetId }
    }
}
