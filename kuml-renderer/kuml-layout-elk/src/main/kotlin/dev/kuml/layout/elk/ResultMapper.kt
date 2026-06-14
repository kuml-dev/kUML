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

        // V3.0.11 — Canvas + Normalisierung in einem Schritt.
        // [buildGroupLayouts] verschiebt Group-Origins um bis zu `headerPx + margin = 44px`
        // in den negativen Y-Bereich (für die Swimlane-/System-Boundary-Kopfzeile).
        // Wird nur `root.children` betrachtet — wie früher in `computeCanvas` —, fällt diese
        // Verschiebung aus der Canvas-Berechnung und damit aus der SVG-viewBox heraus → die
        // Oberkante der Boundary wird abgeschnitten (siehe C4-Container-Beispiel). Daher hier
        // die *tatsächliche* Bounding-Box über Nodes + Groups + Edge-Wegpunkte ziehen und,
        // falls negativ, alles um `(-minX, -minY)` shiften, so dass das Canvas wieder bei
        // 0,0 startet.
        val (canvas, shiftedNodes, shiftedEdges, shiftedGroups) =
            computeNormalizedCanvas(nodeMappings, edgeMappings, groupMappings)

        // seed = null because ELK is non-deterministic (capabilities.deterministic = false)
        return LayoutResult(
            engineId = engineId,
            seed = null,
            canvas = canvas,
            nodes = shiftedNodes,
            edges = shiftedEdges,
            groups = shiftedGroups,
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

    /**
     * Computes [GroupLayout] bounds from the **post-layout node positions** of
     * the group's member nodes rather than from ELK's own compound-node bounds.
     *
     * Background (V2.0.44): ELK's `elk.layered` algorithm does not reliably set
     * `width`/`height` on compound nodes unless a matching sub-algorithm is
     * registered on each group node. Without that, `elkGroup.width/height == 0`
     * and the swimlane rectangles disappear. Computing bounds from member-node
     * positions is engine-agnostic and always correct:
     *  - Walk all [LayoutNode]s that have a `groupId`.
     *  - Look up the corresponding ELK node to get its laid-out position and size.
     *  - Take the axis-aligned bounding box of all member nodes.
     *  - Add a fixed margin and an extra top offset for the swimlane header bar
     *    (matches [dev.kuml.io.svg.sysml2.PARTITION_HEADER_HEIGHT]).
     *
     * If a group has no member nodes (dangling group definition), the group is
     * omitted from the result — matching the renderer's silent-skip convention.
     */
    private fun buildGroupLayouts(builder: ElkGraphBuilder): Map<GroupId, GroupLayout> {
        if (builder.groupMap.isEmpty()) return emptyMap()

        // Build: groupId → list of elk nodes that are members of that group.
        // The original LayoutNode has groupId; its ElkNode has the final position.
        val membersByGroup = mutableMapOf<GroupId, MutableList<org.eclipse.elk.graph.ElkNode>>()
        for (node in builder.nodes()) {
            val gid = node.groupId ?: continue
            val elkNode = builder.nodeMap[node.id] ?: continue
            membersByGroup.getOrPut(gid) { mutableListOf() }.add(elkNode)
        }

        val result = mutableMapOf<GroupId, GroupLayout>()
        // Horizontal / vertical margin around the tightest bounding box of nodes.
        val margin = 16f
        // Extra top space for the swimlane header bar (mirrored from PARTITION_HEADER_HEIGHT).
        val headerPx = 28f

        for (groupId in builder.groupMap.keys) {
            val members = membersByGroup[groupId]
            if (members.isNullOrEmpty()) continue // dangling group — skip

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            for (elkNode in members) {
                val nx = elkNode.x.toFloat()
                val ny = elkNode.y.toFloat()
                val nw = elkNode.width.toFloat()
                val nh = elkNode.height.toFloat()
                if (nx < minX) minX = nx
                if (ny < minY) minY = ny
                if (nx + nw > maxX) maxX = nx + nw
                if (ny + nh > maxY) maxY = ny + nh
            }

            val ox = minX - margin
            val oy = minY - headerPx - margin
            val sw = (maxX - minX) + 2f * margin
            val sh = (maxY - minY) + headerPx + 2f * margin

            result[groupId] = GroupLayout(bounds = Rect(Point(ox, oy), Size(sw, sh)))
        }

        return result
    }

    // ---------------------------------------------------------------------------
    // Canvas
    // ---------------------------------------------------------------------------

    /**
     * Berechnet das tatsächliche Bounding-Box-Canvas über Nodes + Groups + Edge-Wegpunkte
     * und verschiebt — falls eine Komponente in den negativen X/Y-Bereich ragt — alle drei
     * Maps so, dass das Canvas wieder bei 0,0 startet.
     *
     * Hintergrund (V3.0.11): Vor der Normalisierung wurde das Canvas ausschließlich aus
     * den Top-Level-ELK-Kindknoten errechnet. Da [buildGroupLayouts] die Boundary-Bounds
     * um `headerPx + margin = 44px` über das oberste Mitgliedsknoten-Y hinaus nach
     * **oben** zieht (Platz für die Header-Beschriftung der Swimlane/System-Boundary),
     * landeten Group-Origins im negativen Y. Die [Size]-basierte viewBox `0 0 W H` der
     * SVG-Ausgabe schnitt die Boundary-Oberkante dann ab. Hier wird die Bounding-Box
     * über alle drei Mengen gezogen und ggf. ein Translation-Pass angewandt.
     */
    private fun computeNormalizedCanvas(
        nodes: Map<NodeId, NodeLayout>,
        edges: Map<EdgeId, EdgeRoute>,
        groups: Map<GroupId, GroupLayout>,
    ): NormalizedLayout {
        if (nodes.isEmpty() && groups.isEmpty()) {
            return NormalizedLayout(Size(0f, 0f), nodes, edges, groups)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for ((_, layout) in nodes) {
            val r = layout.bounds
            if (r.origin.x < minX) minX = r.origin.x
            if (r.origin.y < minY) minY = r.origin.y
            if (r.origin.x + r.size.width > maxX) maxX = r.origin.x + r.size.width
            if (r.origin.y + r.size.height > maxY) maxY = r.origin.y + r.size.height
        }
        for ((_, layout) in groups) {
            val r = layout.bounds
            if (r.origin.x < minX) minX = r.origin.x
            if (r.origin.y < minY) minY = r.origin.y
            if (r.origin.x + r.size.width > maxX) maxX = r.origin.x + r.size.width
            if (r.origin.y + r.size.height > maxY) maxY = r.origin.y + r.size.height
        }
        for ((_, route) in edges) {
            for (pt in route.allPoints()) {
                if (pt.x < minX) minX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.x > maxX) maxX = pt.x
                if (pt.y > maxY) maxY = pt.y
            }
        }

        // Falls absolut nichts gefunden: 0×0-Canvas.
        if (minX == Float.MAX_VALUE) {
            return NormalizedLayout(Size(0f, 0f), nodes, edges, groups)
        }

        val dx = if (minX < 0f) -minX else 0f
        val dy = if (minY < 0f) -minY else 0f
        val canvas = Size(maxX - minOf(minX, 0f), maxY - minOf(minY, 0f))

        if (dx == 0f && dy == 0f) {
            return NormalizedLayout(canvas, nodes, edges, groups)
        }

        val shiftedNodes =
            nodes.mapValues { (_, l) ->
                l.copy(
                    bounds = l.bounds.copy(origin = Point(l.bounds.origin.x + dx, l.bounds.origin.y + dy)),
                )
            }
        val shiftedGroups =
            groups.mapValues { (_, l) ->
                l.copy(
                    bounds = l.bounds.copy(origin = Point(l.bounds.origin.x + dx, l.bounds.origin.y + dy)),
                )
            }
        val shiftedEdges =
            edges.mapValues { (_, route) ->
                route.shiftBy(dx, dy)
            }
        return NormalizedLayout(canvas, shiftedNodes, shiftedEdges, shiftedGroups)
    }

    private data class NormalizedLayout(
        val canvas: Size,
        val nodes: Map<NodeId, NodeLayout>,
        val edges: Map<EdgeId, EdgeRoute>,
        val groups: Map<GroupId, GroupLayout>,
    )

    private fun Point.shifted(
        dx: Float,
        dy: Float,
    ): Point = Point(x + dx, y + dy)

    /** Liefert alle für die Bounding-Box relevanten Punkte einer Route. */
    private fun EdgeRoute.allPoints(): List<Point> =
        when (this) {
            is EdgeRoute.Direct -> listOf(source, target)
            is EdgeRoute.OrthogonalRounded -> listOf(source) + waypoints + listOf(target)
            is EdgeRoute.TreeRounded -> listOf(source) + waypoints + listOf(target)
            is EdgeRoute.Bezier -> listOf(source) + controlPoints + listOf(target)
        }

    private fun EdgeRoute.shiftBy(
        dx: Float,
        dy: Float,
    ): EdgeRoute {
        if (dx == 0f && dy == 0f) return this
        return when (this) {
            is EdgeRoute.Direct -> copy(source = source.shifted(dx, dy), target = target.shifted(dx, dy))
            is EdgeRoute.OrthogonalRounded ->
                copy(
                    source = source.shifted(dx, dy),
                    target = target.shifted(dx, dy),
                    waypoints = waypoints.map { it.shifted(dx, dy) },
                )
            is EdgeRoute.TreeRounded ->
                copy(
                    source = source.shifted(dx, dy),
                    target = target.shifted(dx, dy),
                    waypoints = waypoints.map { it.shifted(dx, dy) },
                )
            is EdgeRoute.Bezier ->
                copy(
                    source = source.shifted(dx, dy),
                    target = target.shifted(dx, dy),
                    controlPoints = controlPoints.map { it.shifted(dx, dy) },
                )
        }
    }
}
