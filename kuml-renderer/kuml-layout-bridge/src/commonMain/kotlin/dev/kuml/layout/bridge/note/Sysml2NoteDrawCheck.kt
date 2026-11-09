package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.KumlNoteCodes.NoteNotDrawnReason
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.constraint.NoteAnchorKind
import dev.kuml.sysml2.constraint.Sysml2NoteAnchors

/**
 * The one place that decides "a note this diagram declares was not drawn" (`KUML-NOTE-W002`), for every SysML 2
 * diagram kind. It compares what the diagram declares with what the placer really drew, so a note can never vanish
 * silently: the placers filter notes by [AnchorGeometryResolver.canResolve] before placing them, and this check runs
 * against the unfiltered model.
 *
 * A note is *declared* when the diagram lists it in `noteIds`, or when one of its (known) anchors lies in the scope
 * the diagram draws (its `elementIds`, for an IBD also the owner and what the owner contains, level by level).
 * Notes with only unknown anchors are `KUML-NOTE-W001` and never W002; a drawn note is never W002, even when one of
 * several anchors could not be resolved.
 */
public object Sysml2NoteDrawCheck {
    private const val MAX_SCOPE: Int = 5_000

    /** The declared notes of [diagram] that are not in [drawnNoteIds], in declaration order, with the reason. */
    public fun undrawn(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        drawnNoteIds: Set<String>,
    ): List<UndrawnNote> {
        if (model.comments.isEmpty()) return emptyList()
        val known = model.allAnchorableIds()
        val explicit = diagram.noteIds.toSet()
        val scope = scopeOf(model = model, diagram = diagram)
        val supported = Sysml2NoteAnchors.supported(diagram)
        val owner = Sysml2NoteAnchors.ownerOf(diagram)
        val out = mutableListOf<UndrawnNote>()
        for (c in model.comments) {
            if (c.id in drawnNoteIds) continue
            val anchors =
                model.commentLinks
                    .filter { it.link.noteId == c.id }
                    .map { it.link.targetId }
                    .filter { it in known }
            if (anchors.isEmpty()) continue
            if (c.id !in explicit && anchors.none { it in scope }) continue
            val anchor = anchors.firstOrNull { it in scope } ?: anchors.first()
            out +=
                UndrawnNote(
                    noteId = c.id,
                    anchorId = anchor,
                    reason =
                        reasonOf(
                            model = model,
                            anchorId = anchor,
                            supported = supported,
                            owner = owner,
                            drawnIds = diagram.elementIds.toSet(),
                        ),
                    sourceLine = c.note.sourceLine,
                )
        }
        return out
    }

    /**
     * Why the diagram does not show the anchor [anchorId]; the first rule that applies wins:
     *  1. `row-only`: the anchor is a feature of a definition the diagram draws ([drawnIds]), but this diagram kind draws
     *     no box for it, only a row in the definition's compartment;
     *  2. `kind-unsupported`: this diagram kind cannot draw an anchor of that kind anywhere;
     *  3. `nested`: the anchor is a feature of a definition at a deeper level than the diagram draws;
     *  4. `other-level`: anything else (another owner, another diagram's scope).
     */
    internal fun reasonOf(
        model: Sysml2Model,
        anchorId: String,
        supported: Set<NoteAnchorKind>,
        owner: String?,
        drawnIds: Set<String> = emptySet(),
    ): String {
        val kind = Sysml2NoteAnchors.kindOf(model = model, id = anchorId, ownerId = owner)
        val container = model.definitions.firstOrNull { def -> def.features.any { it.id == anchorId } }
        val unsupported = kind != null && kind !in supported
        return when {
            container != null && container.id in drawnIds && unsupported -> NoteNotDrawnReason.ROW_ONLY
            unsupported -> NoteNotDrawnReason.KIND_UNSUPPORTED
            container != null && container.id != owner -> NoteNotDrawnReason.NESTED
            else -> NoteNotDrawnReason.OTHER_LEVEL
        }
    }

    private fun scopeOf(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
    ): Set<String> {
        val scope = LinkedHashSet<String>()
        scope += diagram.elementIds
        // The rows of a drawn definition (its attributes and actions) belong to the diagram's scope, even though only the box is drawn.
        for (id in diagram.elementIds) {
            model.definitions
                .firstOrNull { it.id == id }
                ?.features
                ?.forEach { scope += it.id }
        }
        if (diagram is IbdDiagram) {
            scope += diagram.ownerId
            val partUsages = model.usages.filterIsInstance<PartUsage>().associateBy { it.id }
            val queue = ArrayDeque(listOf(diagram.ownerId))
            val seen = HashSet<String>()
            while (queue.isNotEmpty() && scope.size < MAX_SCOPE) {
                val defId = queue.removeFirst()
                val def = model.definitions.firstOrNull { it.id == defId } ?: continue
                if (!seen.add(def.id)) continue
                for (f in def.features) {
                    scope += f.id
                    (partUsages[f.id])?.let { queue.addLast(it.definitionId) }
                }
            }
        }
        return scope
    }
}
