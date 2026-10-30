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
