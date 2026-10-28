package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model

/**
 * Adds the visible SysML 2 comments of [diagram] as nodes (sized directly via
 * [NoteLayout], bypassing the diagram's `SizeProvider`, whose `kindHint`
 * `when` would hand a note the 220x140 default) and their anchor links as
 * edges. Comments whose id collides with a graph node id are skipped.
 */
internal fun LayoutGraph.withSysml2Notes(
    model: Sysml2Model,
    diagram: Sysml2Diagram,
    extraVisibleIds: Set<String> = emptySet(),
): LayoutGraph {
    if (model.comments.isEmpty()) return this
    // Group ids that double as edge endpoints (STM composite states and regions): the ELK
    // builder resolves an endpoint that is no node to the group of the same id.
    val visible = nodes.map { it.id.value }.toSet() + extraVisibleIds
    val notes = model.notesVisibleIn(diagram = diagram, visibleNodeIds = visible)
    val comments = notes.comments.filter { it.id !in visible }
    if (comments.isEmpty()) return this
    val shown = comments.map { it.id }.toSet()
    val noteNodes =
        comments.map {
            LayoutNode(id = NodeId(it.id), intrinsicSize = NoteLayout.sizeOf(body = it.note.body, frame = NoteFrameStyle.DOG_EAR))
        }
    val noteEdges =
        notes.links
            .filter { it.link.noteId in shown && it.link.targetId in visible }
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

/**
 * Sequence diagrams are laid out by ELK without edges (messages are drawn by
 * the renderer), so notes are placed after the layout: in one column to the
 * right of the rightmost lifeline, stacked in declaration order starting at
 * the top of the lifeline heads. Each anchor link becomes a direct horizontal
 * route from the note to the right edge of the target lifeline head. The canvas grows accordingly.
 */
public fun Sysml2LayoutBridge.placeSeqNotes(
    model: Sysml2Model,
    diagram: SeqDiagram,
    layoutResult: LayoutResult,
): LayoutResult {
    if (model.comments.isEmpty() || layoutResult.nodes.isEmpty()) return layoutResult
    val visible =
        layoutResult.nodes.keys
            .map { it.value }
            .toSet()
    val notes = model.notesVisibleIn(diagram = diagram, visibleNodeIds = visible)
    val comments = notes.comments.filter { it.id !in visible }
    if (comments.isEmpty()) return layoutResult

    val maxRight = layoutResult.nodes.values.maxOf { it.bounds.origin.x + it.bounds.size.width }
    val topY = layoutResult.nodes.values.minOf { it.bounds.origin.y }
    val x = maxRight + SEQ_NOTE_GAP_X
    var y = topY
    val newNodes = LinkedHashMap(layoutResult.nodes)
    val newEdges = LinkedHashMap(layoutResult.edges)
    var right = x
    for (c in comments) {
        val size: Size = NoteLayout.sizeOf(body = c.note.body, frame = NoteFrameStyle.DOG_EAR)
        newNodes[NodeId(c.id)] = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = size))
        right = maxOf(right, x + size.width)
        val midY = y + size.height / 2f
        for (l in notes.links.filter { it.link.noteId == c.id }) {
            val target = layoutResult.nodes[NodeId(l.link.targetId)] ?: continue
            val targetX = target.bounds.origin.x + target.bounds.size.width
            newEdges[EdgeId(l.id)] = EdgeRoute.Direct(source = Point(x = x, y = midY), target = Point(x = targetX, y = midY))
        }
        y += size.height + SEQ_NOTE_GAP_Y
    }
    val canvas =
        Size(
            width = maxOf(layoutResult.canvas.width, right + SEQ_NOTE_GAP_X / 2f),
            height = maxOf(layoutResult.canvas.height, y),
        )
    return layoutResult.copy(canvas = canvas, nodes = newNodes, edges = newEdges)
}

private const val SEQ_NOTE_GAP_X: Float = 40f
private const val SEQ_NOTE_GAP_Y: Float = 16f
