package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.LayoutWarning
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Übersetzt einen gelayouteten ELK-Graphen zurück in ein [LayoutResult].
 *
 * Kein ELK-Typ taucht in einer public-Signatur auf — alle ELK-Imports sind `internal`.
 *
 * Heuristik für Kanten-Routing-Stil:
 * - 0 Bend-Points → [EdgeRoute.Direct]
 * - ≥ 1 Bend-Point → [EdgeRoute.OrthogonalRounded] mit `cornerRadiusPx = 0f`
 *   (Rundung erfolgt später im Renderer, nicht hier — Spec Z4)
 */
internal object ResultMapper {
    /**
     * Konvertiert den layouteten ELK-Graphen vollständig in einen [LayoutResult].
     *
     * @param engineId Die ID der verwendeten Engine.
     * @param builder Der [ElkGraphBuilder], der die Knoten-/Kanten-Mappings hält.
     * @param root Der gelayoutete ELK-Root-Knoten.
     * @param warnings Bereits gesammelte Warnungen (z.B. aus [HintsMapper]).
     */
    fun toLayoutResult(
        engineId: LayoutEngineId,
        builder: ElkGraphBuilder,
        root: ElkNode,
        warnings: List<LayoutWarning>,
        nodeCount: Int,
    ): LayoutResult {
        val allWarnings = warnings.toMutableList()

        // Warn if graph exceeds recommended size
        if (nodeCount > 500) {
            allWarnings.add(
                LayoutWarning(
                    code = "graph.size.large",
                    message =
                        "Graph has $nodeCount nodes, which exceeds the recommended maximum of 500. " +
                            "Layout quality and performance may degrade.",
                ),
            )
        }

        val nodeMappings = buildNodeLayouts(builder)
        val edgeMappings = buildEdgeRoutes(builder)
        val groupMappings = buildGroupLayouts(builder)

        val canvas = computeCanvas(root)

        // seed = null because ELK is non-deterministic (capabilities.deterministic = false)
        return LayoutResult(
            engineId = engineId,
            seed = null,
            canvas = canvas,
            nodes = nodeMappings,
            edges = edgeMappings,
            groups = groupMappings,
            warnings = allWarnings,
        )
    }

    // ---------------------------------------------------------------------------
    // Nodes
    // ---------------------------------------------------------------------------

    private fun buildNodeLayouts(builder: ElkGraphBuilder): Map<NodeId, NodeLayout> {
        val result = mutableMapOf<NodeId, NodeLayout>()
        for ((nodeId, elkNode) in builder.nodeMap) {
            val origin = Point(elkNode.x.toFloat(), elkNode.y.toFloat())
            val size = Size(elkNode.width.toFloat(), elkNode.height.toFloat())
            result[nodeId] = NodeLayout(bounds = Rect(origin, size))
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // Edges
    // ---------------------------------------------------------------------------

    private fun buildEdgeRoutes(builder: ElkGraphBuilder): Map<EdgeId, EdgeRoute> {
        val result = mutableMapOf<EdgeId, EdgeRoute>()
        for ((edgeId, elkEdge) in builder.edgeIdToElkEdge) {
            val section = ElkGraphUtil.firstEdgeSection(elkEdge, false, false) ?: continue
            val source = Point(section.startX.toFloat(), section.startY.toFloat())
            val target = Point(section.endX.toFloat(), section.endY.toFloat())
            val bendPoints = section.bendPoints

            val route: EdgeRoute =
                if (bendPoints.isEmpty()) {
                    EdgeRoute.Direct(source = source, target = target)
                } else {
                    val waypoints = bendPoints.map { Point(it.x.toFloat(), it.y.toFloat()) }
                    // cornerRadiusPx = 0f: Renderer applies rounding later (Spec Z4)
                    EdgeRoute.OrthogonalRounded(
                        source = source,
                        target = target,
                        waypoints = waypoints,
                        cornerRadiusPx = 0f,
                    )
                }

            result[edgeId] = route
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------------------

    private fun buildGroupLayouts(builder: ElkGraphBuilder): Map<GroupId, GroupLayout> {
        val result = mutableMapOf<GroupId, GroupLayout>()
        for ((groupId, elkGroup) in builder.groupMap) {
            val origin = Point(elkGroup.x.toFloat(), elkGroup.y.toFloat())
            val size = Size(elkGroup.width.toFloat(), elkGroup.height.toFloat())
            result[groupId] = GroupLayout(bounds = Rect(origin, size))
        }
        return result
    }

    // ---------------------------------------------------------------------------
    // Canvas
    // ---------------------------------------------------------------------------

    /**
     * Berechnet die Canvas-Größe aus den Top-Level-Kindknoten des ELK-Root-Knotens.
     * Ist der Graph leer, wird ein 0×0-Canvas zurückgegeben.
     */
    private fun computeCanvas(root: ElkNode): Size {
        var maxX = 0.0
        var maxY = 0.0
        for (child in root.children) {
            maxX = maxOf(maxX, child.x + child.width)
            maxY = maxOf(maxY, child.y + child.height)
        }
        return Size(maxX.toFloat(), maxY.toFloat())
    }
}
