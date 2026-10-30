package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.sysml2IbdProjection

/**
 * Anchor geometries that are not plain layout nodes, edges or groups: they come from information only the
 * diagram bridge has (port positions stored relative to their part, the owner of a frame-less IBD).
 */
public object Sysml2NoteAnchorExtras {
    /**
     * IBD: part ports (by port usage id `Battery::dcOut` and by usage path `Vehicle::battery::dcOut`) and the
     * owner. The owner frame is a layout group (`frame:<owner>`) when the diagram draws one; without a frame
     * the owner anchors to the box that surrounds all visible parts.
     */
    public fun ibd(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layout: LayoutResult,
    ): Map<String, AnchorGeometry> {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val out = LinkedHashMap<String, AnchorGeometry>()
        val ends = (projection.connections + projection.flows + projection.bindings).flatMap { listOf(it.source, it.target) }
        for (end in ends) {
            val nodeId = end.nodeId ?: continue
            if (end.steps.isEmpty()) continue
            val node = layout.nodes[NodeId(nodeId)] ?: continue
            val key = end.steps.joinToString("::")
            val local = node.ports[PortId(key)] ?: continue
            // IBD stores part-port positions relative to the part box.
            val abs = Point(x = node.bounds.origin.x + local.x, y = node.bounds.origin.y + local.y)
            if ("$nodeId::$key" !in out) out["$nodeId::$key"] = AnchorGeometry.At(abs)
            end.terminalPort?.let { if (it.id !in out) out[it.id] = AnchorGeometry.At(abs) }
        }
        if (layout.groups[Sysml2LayoutBridge.ibdFrameGroupId(diagram.ownerId)] == null) {
            val partRects = projection.partNodes.mapNotNull { layout.nodes[NodeId(it.id)]?.bounds }
            if (partRects.isNotEmpty()) {
                out[diagram.ownerId] = AnchorGeometry.Around(partRects.reduce { a, b -> a.union(b) }.inflated(OWNER_BOX_PAD))
            }
        }
        return out
    }

    private const val OWNER_BOX_PAD: Float = 12f
}
