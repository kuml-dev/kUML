package dev.kuml.layout.grid

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.LayoutWarning
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Pure-Kotlin Grid-Layout-Engine.
 *
 * Im Gegensatz zur ELK-basierten Engine ([dev.kuml.layout.elk.ElkLayoutEngine])
 * hat diese Engine **keine** Abhängigkeit auf ELK, EMF oder Xtext und ist damit
 * GraalVM-Native-Image-tauglich. Sie ist absichtlich algorithmisch einfach
 * (deterministisch, O(n²) worst case) und für Diagramme bis ca. 500 Knoten
 * gedacht — ab da Warning `engine.performance.large_graph`.
 *
 * **Algorithmus** (zerlegt in vier Phasen):
 *
 *  1. **Slot-Allokation** ([placeOnGrid]) — explizite Hints → relative
 *     Constraints → Auto-Fill in Reading-Order.
 *  2. **Spalten-/Zeilen-Sizing** ([computeGeometry]) — Max-Breite/Höhe pro
 *     Spalte/Zeile, Spacing aus [LayoutHints.spacing].
 *  3. **Knoten-Platzierung** ([CanvasGeometry.boundsFor]) — jeden Knoten in
 *     seiner Zelle zentrieren.
 *  4. **Edge-Routing** ([routeEdge]) — pro Kante den passenden Stil aus
 *     `EdgeHints.routeStyle` ?: `LayoutHints.defaultEdgeStyle` anwenden.
 *
 * **Determinismus**: gleiche Eingabe + gleicher Seed liefert bit-identisches
 * Resultat. Der Seed wird hier zwar entgegengenommen, aber nicht verwendet
 * (keine Zufalls-Komponente im Algorithmus) — der `LayoutResult.seed`-Slot
 * wird trotzdem korrekt befüllt, damit Goldfile-Tests den Wert sehen.
 *
 * **Performance**:
 *  - Bis 100 Knoten: ≪ 10 ms.
 *  - Bis 500 Knoten: noch flüssig (typischerweise < 100 ms).
 *  - > 500 Knoten: Warning `engine.performance.large_graph`. Empfehlung:
 *    ELK-Engine für solche Größen.
 */
public class GridLayoutEngine : KumlLayoutEngine {
    public override val id: LayoutEngineId = LayoutEngineId("kuml.grid")

    public override val capabilities: LayoutCapabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds =
                setOf(
                    DiagramKind.UmlClass,
                    DiagramKind.UmlComponent,
                    DiagramKind.UmlUseCase,
                    DiagramKind.UmlState,
                    DiagramKind.C4Container,
                    DiagramKind.C4Component,
                    DiagramKind.C4Deployment,
                    DiagramKind.Generic,
                ),
            supportedEdgeStyles =
                setOf(
                    EdgeRouteStyle.Direct,
                    EdgeRouteStyle.OrthogonalRounded,
                    EdgeRouteStyle.TreeRounded,
                    EdgeRouteStyle.Bezier,
                ),
            respectsGridHints = true,
            respectsRelativeConstraints = true,
            maxRecommendedNodes = 500,
        )

    public override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult {
        val warnings = mutableListOf<LayoutWarning>()

        // ── Performance-Vorwarnung für sehr große Graphen ─────────────────
        if (graph.nodes.size > capabilities.maxRecommendedNodes) {
            warnings +=
                LayoutWarning(
                    code = "engine.performance.large_graph",
                    message =
                        "Grid engine received ${graph.nodes.size} nodes; recommended max is " +
                            "${capabilities.maxRecommendedNodes}. Consider the ELK engine for " +
                            "graphs of this size.",
                )
        }

        // ── Phase 1: Slot-Allokation ──────────────────────────────────────
        val placement = placeOnGrid(graph)
        warnings += placement.warnings

        // ── Phase 2: Spalten-/Zeilen-Geometrie ────────────────────────────
        val geometry = computeGeometry(graph, placement, hints.spacing)

        // ── Phase 3: Knoten-Bounds + Ports ────────────────────────────────
        val portsByNode = portsByNode(graph.edges)
        val nodeLayouts: Map<NodeId, NodeLayout> =
            graph.nodes.associate { node ->
                val slot = placement.slots.getValue(node.id)
                val bounds = geometry.boundsFor(slot, node.intrinsicSize)
                val portIds = portsByNode[node.id].orEmpty()
                node.id to NodeLayout(bounds = bounds, ports = allocatePorts(bounds, portIds))
            }

        // ── Phase 4: Edge-Routing ─────────────────────────────────────────
        val edgeRoutes: Map<EdgeId, EdgeRoute> =
            graph.edges.associate { edge ->
                val style = edge.hints.routeStyle ?: hints.defaultEdgeStyle
                val sourceLayout = nodeLayouts.getValue(edge.source.nodeId)
                val targetLayout = nodeLayouts.getValue(edge.target.nodeId)
                val targetCenter = centerOf(targetLayout.bounds)
                val sourceCenter = centerOf(sourceLayout.bounds)
                val sourcePt = endpointPoint(edge.source, nodeLayouts, targetCenter)
                val targetPt = endpointPoint(edge.target, nodeLayouts, sourceCenter)
                edge.id to routeEdge(sourcePt, targetPt, style)
            }

        // ── Group bounds: umschließendes Rechteck aller Kindknoten + Padding ──
        val groupLayouts = computeGroupBounds(graph, nodeLayouts, warnings)

        return LayoutResult(
            engineId = id,
            seed = hints.deterministicSeed,
            canvas = geometry.canvasSize,
            nodes = nodeLayouts,
            edges = edgeRoutes,
            groups = groupLayouts,
            warnings = warnings.toList(),
        )
    }

    /**
     * Berechnet umschließende Rechtecke für alle Gruppen.
     *
     * Strategie: für jede Gruppe sammle alle Knoten mit passender `groupId`,
     * bilde den Bounding-Box-Umriss und addiere das Gruppen-Padding.
     *
     * Verschachtelte Gruppen werden iterativ verarbeitet — Kindgruppen müssen
     * vor Elterngruppen aufgelöst werden (eine Eltern-Gruppe enthält die
     * Bounds ihrer Kindgruppen).
     */
    private fun computeGroupBounds(
        graph: LayoutGraph,
        nodeLayouts: Map<NodeId, NodeLayout>,
        warnings: MutableList<LayoutWarning>,
    ): Map<dev.kuml.layout.GroupId, GroupLayout> {
        if (graph.groups.isEmpty()) return emptyMap()
        val result = mutableMapOf<dev.kuml.layout.GroupId, GroupLayout>()
        val children: Map<dev.kuml.layout.GroupId, List<NodeId>> =
            graph.nodes
                .filter { it.groupId != null }
                .groupBy { it.groupId!! }
                .mapValues { entry -> entry.value.map { it.id } }

        // Topologisch nach Eltern-Tiefe sortieren: Kinder zuerst, Eltern zuletzt.
        val depths = mutableMapOf<dev.kuml.layout.GroupId, Int>()
        for (group in graph.groups) {
            var d = 0
            var cursor = group.parent
            while (cursor != null) {
                d++
                cursor = graph.groups.firstOrNull { it.id == cursor }?.parent
                if (d > graph.groups.size) {
                    warnings +=
                        LayoutWarning(
                            code = "group.cycle.detected",
                            message =
                                "Cycle detected in group hierarchy near '${group.id.value}'; " +
                                    "treating remaining ancestors as flat.",
                        )
                    break
                }
            }
            depths[group.id] = d
        }
        val sortedDeepestFirst = graph.groups.sortedByDescending { depths[it.id] ?: 0 }

        for (group in sortedDeepestFirst) {
            val ownChildren = children[group.id].orEmpty().mapNotNull { nodeLayouts[it]?.bounds }
            val childGroupBounds =
                graph.groups
                    .filter { it.parent == group.id }
                    .mapNotNull { result[it.id]?.bounds }
            val allBounds = ownChildren + childGroupBounds
            if (allBounds.isEmpty()) continue
            val box = unionBounds(allBounds)
            val padded =
                Rect(
                    origin =
                        Point(
                            x = box.origin.x - group.padding.left,
                            y = box.origin.y - group.padding.top,
                        ),
                    size =
                        Size(
                            width = box.size.width + group.padding.left + group.padding.right,
                            height = box.size.height + group.padding.top + group.padding.bottom,
                        ),
                )
            result[group.id] = GroupLayout(bounds = padded)
        }
        return result
    }

    private fun unionBounds(rects: List<Rect>): Rect {
        val minX = rects.minOf { it.origin.x }
        val minY = rects.minOf { it.origin.y }
        val maxX = rects.maxOf { it.origin.x + it.size.width }
        val maxY = rects.maxOf { it.origin.y + it.size.height }
        return Rect(origin = Point(minX, minY), size = Size(maxX - minX, maxY - minY))
    }
}
