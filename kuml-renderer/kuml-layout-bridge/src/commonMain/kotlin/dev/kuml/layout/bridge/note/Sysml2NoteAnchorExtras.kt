package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect
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
            // IBD stores part-port positions relative to the part box; the square is the one the renderer draws.
            val box = Sysml2LayoutBridge.ibdPortSquare(boxBounds = node.bounds, local = local)
            val outward = sideOf(Sysml2LayoutBridge.ibdNearestSide(p = local, w = node.bounds.size.width, h = node.bounds.size.height))
            val geometry = AnchorGeometry.Port(box = box, outward = outward)
            if ("$nodeId::$key" !in out) out["$nodeId::$key"] = geometry
            end.terminalPort?.let { if (it.id !in out) out[it.id] = geometry }
        }
        if (layout.groups[Sysml2LayoutBridge.ibdFrameGroupId(diagram.ownerId)] == null) {
            val partRects = projection.partNodes.mapNotNull { layout.nodes[NodeId(it.id)]?.bounds }
            if (partRects.isNotEmpty()) {
                out[diagram.ownerId] = AnchorGeometry.Around(partRects.reduce { a, b -> a.union(b) }.inflated(OWNER_BOX_PAD))
            }
        }
        return out
    }

    /**
     * Registers what the drawn ports block: the port squares and the estimated rectangles of their labels (placed
     * by the same function the renderer uses), so a note keeps clear of both.
     */
    public fun ibdPortObstacles(
        obstacles: ObstacleIndex,
        extras: Map<String, AnchorGeometry>,
        layout: LayoutResult,
    ) {
        val seen = HashSet<Rect>()
        for (g in extras.values) {
            val port = g as? AnchorGeometry.Port ?: continue
            if (seen.add(port.box)) obstacles.addRect(kind = ObstacleKind.PORT, r = port.box)
        }
        for (node in layout.nodes.values) {
            if (node.ports.isEmpty()) continue
            val placements =
                Sysml2LayoutBridge.ibdPartPortPlacements(
                    boxSize = node.bounds.size,
                    ports = node.ports.mapKeys { it.key.value },
                    labels = node.ports.keys.associate { it.value to it.value.substringAfterLast("::") },
                )
            for ((_, placement) in placements.values) {
                val r = placement.rect
                obstacles.addRect(
                    kind = ObstacleKind.PORT_LABEL,
                    r = Rect(origin = Point(x = node.bounds.origin.x + r.origin.x, y = node.bounds.origin.y + r.origin.y), size = r.size),
                    ownerId = null,
                )
            }
        }
    }

    private fun sideOf(s: Sysml2LayoutBridge.IbdSide): Side =
        when (s) {
            Sysml2LayoutBridge.IbdSide.Left -> Side.W
            Sysml2LayoutBridge.IbdSide.Right -> Side.E
            Sysml2LayoutBridge.IbdSide.Top -> Side.N
            Sysml2LayoutBridge.IbdSide.Bottom -> Side.S
        }

    private const val OWNER_BOX_PAD: Float = 12f
}
