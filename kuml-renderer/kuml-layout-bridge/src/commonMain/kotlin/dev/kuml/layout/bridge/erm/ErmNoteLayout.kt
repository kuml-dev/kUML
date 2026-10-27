package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.ErmModel
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout

/**
 * Adds all notes of [model] as nodes plus one edge per link whose target node
 * exists. An ERM diagram always shows the whole model, so every note is shown.
 *
 * [resolveTarget] maps an anchor id (entity / view id) to the id of the layout
 * node that represents it (identity for Martin/Bachman/IDEF1X, a prefixed
 * synthetic id for Chen); it returns `null` when that node is not present.
 * Notes whose id collides with an existing node id are skipped.
 */
internal fun LayoutGraph.withErmNotes(
    model: ErmModel,
    resolveTarget: (String) -> String?,
): LayoutGraph {
    if (model.notes.isEmpty()) return this
    val present = nodes.map { it.id.value }.toSet()
    val notes = model.notes.filter { it.id !in present }
    if (notes.isEmpty()) return this
    val noteIds = notes.map { it.id }.toSet()
    val noteNodes =
        notes.map {
            LayoutNode(id = NodeId(it.id), intrinsicSize = NoteLayout.sizeOf(body = it.note.body, frame = NoteFrameStyle.DOG_EAR))
        }
    val noteEdges =
        model.noteLinks.mapNotNull { l ->
            if (l.link.noteId !in noteIds) return@mapNotNull null
            val target = resolveTarget(l.link.targetId)?.takeIf { it in present } ?: return@mapNotNull null
            LayoutEdge(
                id = EdgeId(l.id),
                source = EndpointRef(nodeId = NodeId(l.link.noteId)),
                target = EndpointRef(nodeId = NodeId(target)),
                hints = EdgeHints.NONE,
            )
        }
    return copy(nodes = nodes + noteNodes, edges = edges + noteEdges)
}
