package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.isHint
import dev.kuml.erm.model.ErmModel
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge

/**
 * Note placement for ERM diagrams (Nacharbeit 100). ERM notes are ordinary ELK nodes joined to their entity or view
 * by note-link edges, so ELK decides where they sit. A note that carries a side hint (ABOVE/BELOW/LEFT/RIGHT) is
 * instead taken out of the ELK result and placed by [NotePlacer] against the finished layout, exactly like the C4,
 * SysML 2 and UML notes; a hint that no free position on the requested side can satisfy falls back to the default
 * rules and is reported as `KUML-NOTE-W004`.
 *
 * Since Nacharbeit 120 a hinted note also avoids the (estimated) name label at the centre of a relationship edge;
 * cardinalities and roles are not yet obstacles, and notes without a hint still ignore labels.
 *
 * Notes without a hint (and NEAR hints), notes without a resolvable anchor and every layout without a hinted note stay
 * byte-identical: [place] returns the layout it was given.
 */
public object ErmNotePlacement {
    /**
     * Maps a note-link target (plain entity or view id) to the id of its Chen layout node
     * (`chen-entity::<id>` or `chen-view::<id>`), for use as `resolveTarget` of [place].
     */
    public fun chenTarget(layout: LayoutResult): (String) -> String {
        val ids =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        return { id ->
            if (ErmChenLayoutBridge.ENTITY_PREFIX + id in ids) {
                ErmChenLayoutBridge.ENTITY_PREFIX + id
            } else {
                ErmChenLayoutBridge.VIEW_PREFIX + id
            }
        }
    }

    /**
     * Places the hinted notes of [model] in [layout]; returns [layout] unchanged when there is nothing to do.
     * [resolveTarget] maps a link target id to the layout node id (identity except for Chen, see [chenTarget]).
     */
    public fun place(
        model: ErmModel,
        layout: LayoutResult,
        resolveTarget: (String) -> String = { it },
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val none = NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val layoutIds =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        val hinted = model.notes.filter { it.note.placement.isHint() && it.id in layoutIds }
        if (hinted.isEmpty()) return none
        val hintedIds = hinted.map { it.id }.toSet()
        val linkIdsOfHinted =
            model.noteLinks
                .filter { it.link.noteId in hintedIds }
                .map { it.id }
                .toSet()

        // The layout without the hinted notes and their anchor lines: what the placer measures against.
        val stripped =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in hintedIds },
                edges = layout.edges.filterKeys { it.value !in linkIdsOfHinted },
            )
        val resolver = AnchorGeometryResolver(layout = stripped)
        val inputs =
            hinted.mapNotNull { n ->
                val anchors =
                    model.noteLinks
                        .filter { it.link.noteId == n.id }
                        .mapNotNull { l ->
                            val target = resolveTarget(l.link.targetId)
                            if (resolver.canResolve(target)) NoteAnchorRef(linkId = l.id, targetId = target) else null
                        }
                if (anchors.isEmpty()) {
                    null
                } else {
                    NotePlacementInput(
                        noteId = n.id,
                        body = n.note.body,
                        kind = n.note.kind,
                        placement = n.note.placement,
                        anchors = anchors,
                    )
                }
            }
        if (inputs.isEmpty()) return none

        // Only the notes the placer takes over leave the ELK result; every other note stays where ELK put it.
        val taken = inputs.map { it.noteId }.toSet()
        val takenLinks =
            model.noteLinks
                .filter { it.link.noteId in taken }
                .map { it.id }
                .toSet()
        val base =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in taken },
                edges = layout.edges.filterKeys { it.value !in takenLinks },
            )
        val labels =
            model.relationships
                .filter { !it.name.isNullOrBlank() }
                .associate { it.id to listOf(it.name.orEmpty()) }
        val placed =
            NotePlacer.place(
                layout = base,
                notes = inputs,
                resolver = AnchorGeometryResolver(layout = base),
                // Nacharbeit 120: the relationship name at the edge centre is an obstacle (cardinalities and roles are not yet).
                obstacles = Sysml2NotePlacement.obstaclesOf(layout = base, edgeLabels = { id -> labels[id].orEmpty() }),
                config = config,
            )
        if (placed.hintNotHonoured.isEmpty()) return placed
        val lines = model.notes.associate { it.id to it.note.sourceLine }
        val misses = placed.hintNotHonoured.map { it.copy(sourceLine = lines[it.noteId]) }
        NotePlacementMissCollector.report(misses)
        return placed.copy(hintNotHonoured = misses)
    }
}
