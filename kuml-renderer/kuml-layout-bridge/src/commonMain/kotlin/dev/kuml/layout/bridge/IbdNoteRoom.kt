package dev.kuml.layout.bridge

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.NodeId
import dev.kuml.layout.NoteRoom
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.DefaultNoteMeasure
import dev.kuml.layout.bridge.note.NotePlacementConfig
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.sysml2IbdProjection

/**
 * Note room of an IBD (PAL Welle Y4): which `ABOVE` / `BELOW` notes may reserve room above or below their part box in the
 * ELK layout, and how much. The layout bridge (room per node) and the note placement glue (home of the note) both derive
 * their data from here, so they cannot drift apart, like [ReqNoteRoom] does for requirement groups.
 *
 * Only a note with exactly one anchor that is a top-level part box is eligible. Notes on connectors, ports, nested parts
 * or with several anchors keep the general placement.
 */
internal object IbdNoteRoom {
    /** Gap between stacked notes. */
    const val GAP_PX: Float = 12f

    /** Gap the placer keeps between the box and the note ([NotePlacementConfig.candidateGap]). */
    const val ANCHOR_GAP_PX: Float = 24f

    /** Clearance the placer wants between the note and the part (or line) on the far side; measured: 13 px fails, 17 px fits. */
    const val CLEARANCE_PX: Float = 16f

    /** At most this many notes per side of a node reserve room; further notes take the general placement. */
    const val MAX_PER_SIDE: Int = 2

    /** Upper bound of the room of one side (denial of service guard). */
    const val CAP_PX: Float = 200f

    /** At most this many nodes of one diagram reserve room. */
    const val MAX_NODES: Int = 16

    /** The notes (id and measured size) of one side of one node, in declaration order. */
    class Reserved(
        val nodeId: String,
        val side: NotePlacement,
        val notes: List<Pair<String, Size>>,
    )

    /**
     * The eligible notes, grouped per node and side. [onlyNoteIds] restricts them to the notes that missed their hint in a
     * first run (`null` = all eligible notes).
     */
    fun eligible(
        model: Sysml2Model,
        diagram: IbdDiagram,
        onlyNoteIds: Set<String>? = null,
    ): List<Reserved> {
        if (model.comments.isEmpty()) return emptyList()
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val partIds = projection.partNodes.map { it.id }.toSet()
        if (partIds.isEmpty()) return emptyList()
        val shown = model.notesVisibleIn(diagram = diagram, visibleNodeIds = partIds)
        val maxWidth = NotePlacementConfig().maxNoteWidth
        val groups = LinkedHashMap<Pair<String, NotePlacement>, MutableList<Pair<String, Size>>>()
        for (c in shown.comments) {
            val side = c.note.placement
            if (side != NotePlacement.ABOVE && side != NotePlacement.BELOW) continue
            if (onlyNoteIds != null && c.id !in onlyNoteIds) continue
            val targets =
                model.commentLinks
                    .filter { it.link.noteId == c.id }
                    .map { it.link.targetId }
                    .distinct()
            val anchor = targets.singleOrNull() ?: continue
            if (anchor !in partIds) continue
            val size = DefaultNoteMeasure.measure(body = c.note.body, maxWidth = maxWidth, kind = c.note.kind)
            val list = groups.getOrPut(anchor to side) { ArrayList() }
            if (list.size < MAX_PER_SIDE) list += c.id to size
        }
        return groups.entries
            .map { (key, notes) -> Reserved(nodeId = key.first, side = key.second, notes = notes) }
            .let { all ->
                val nodes =
                    all
                        .map { it.nodeId }
                        .distinct()
                        .take(MAX_NODES)
                        .toSet()
                all.filter { it.nodeId in nodes }
            }
    }

    /**
     * Height of the room for [notes]: the gap to the box, the notes with a gap between them and the clearance to what lies
     * beyond, capped at [CAP_PX]. ELK adds its own layer spacing on top, so the notes always fit.
     */
    fun heightOf(notes: List<Pair<String, Size>>): Float =
        minOf(
            CAP_PX,
            ANCHOR_GAP_PX + notes.sumOf { it.second.height.toDouble() }.toFloat() + GAP_PX * (notes.size - 1) + CLEARANCE_PX,
        )

    /** The room per node: [NoteRoom.top] for the ABOVE notes, [NoteRoom.bottom] for the BELOW notes. */
    fun rooms(reserved: List<Reserved>): Map<NodeId, NoteRoom> {
        val out = LinkedHashMap<NodeId, NoteRoom>()
        for (r in reserved) {
            val id = NodeId(r.nodeId)
            val old = out[id] ?: NoteRoom()
            val h = heightOf(r.notes)
            out[id] = if (r.side == NotePlacement.ABOVE) old.copy(top = h) else old.copy(bottom = h)
        }
        return out
    }
}
