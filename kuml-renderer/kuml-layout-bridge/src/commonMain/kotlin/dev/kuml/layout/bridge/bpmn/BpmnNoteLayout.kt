package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnTextAnnotation
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
 * Adds text annotations as nodes (bracket frame, sized via [NoteLayout]) and
 * their associations as edges.
 *
 * Visibility: with no [filterIds] (diagram shows everything) all annotations
 * are shown; otherwise an annotation is shown when its id is listed or at
 * least one anchor is visible. Associations to invisible targets are dropped.
 * Annotations whose id collides with an existing node id are skipped.
 */
internal fun LayoutGraph.withBpmnNotes(
    annotations: List<BpmnTextAnnotation>,
    associations: List<BpmnAssociation>,
    filterIds: Set<String>?,
): LayoutGraph {
    if (annotations.isEmpty()) return this
    val present = nodes.map { it.id.value }.toSet()
    val anchoredVisible =
        associations
            .filter { it.link.targetId in present }
            .map { it.link.noteId }
            .toSet()
    val shown =
        annotations.filter { a ->
            a.id !in present && (filterIds == null || a.id in filterIds || a.id in anchoredVisible)
        }
    if (shown.isEmpty()) return this
    val shownIds = shown.map { it.id }.toSet()
    val noteNodes =
        shown.map {
            LayoutNode(id = NodeId(it.id), intrinsicSize = NoteLayout.sizeOf(body = it.note.body, frame = NoteFrameStyle.BRACKET))
        }
    val noteEdges =
        associations
            .filter { it.link.noteId in shownIds && it.link.targetId in present }
            .map {
                LayoutEdge(
                    id = EdgeId(it.id),
                    source = EndpointRef(nodeId = NodeId(it.link.noteId)),
                    target = EndpointRef(nodeId = NodeId(it.link.targetId)),
                    hints = EdgeHints.NONE,
                )
            }
    return copy(nodes = nodes + noteNodes, edges = edges + noteEdges)
}
