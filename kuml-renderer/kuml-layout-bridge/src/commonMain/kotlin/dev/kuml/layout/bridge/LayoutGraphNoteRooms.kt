package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.NoteRoom

/**
 * PAL Welle Y4: this graph with [rooms] set on the nodes (see [LayoutNode.noteRoom]). With no rooms it returns the same
 * object, so a diagram without a qualifying note lays out exactly as before.
 */
public fun LayoutGraph.withNoteRooms(rooms: Map<NodeId, NoteRoom>): LayoutGraph =
    if (rooms.isEmpty()) {
        this
    } else {
        copy(nodes = nodes.map { n -> rooms[n.id]?.let { n.copy(noteRoom = it) } ?: n })
    }
