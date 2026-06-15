package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.Insets
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
        // [buildGroupLayouts] verschiebt Group-Origins um bis zu `headerPx + margin = 34px`
        // (V2.0.45; vorher 44px) in den negativen Y-Bereich (für die Swimlane-/
        // System-Boundary-Kopfzeile).
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
            // V11.x — ELK reports child positions relative to the parent
            // compound; flat-root nodes have parent == root. Walking up adds
            // each ancestor's offset and yields absolute canvas coordinates,
            // which is what the renderer / edge router expect.
            val (absX, absY) = absolutePosition(elkNode)
            val origin = Point(absX, absY)
            val size = Size(elkNode.width.toFloat(), elkNode.height.toFloat())
            result[nodeId] = NodeLayout(bounds = Rect(origin, size))
        }
        return result
    }

    /** Walks `node.parent` up to the (root) and accumulates the X/Y offsets so
     *  callers see a single absolute canvas coordinate. The ELK root itself
     *  reports `(0, 0)` and therefore drops out. */
    private fun absolutePosition(node: ElkNode): Pair<Float, Float> {
        var x = node.x
        var y = node.y
        var p: ElkNode? = node.parent
        while (p != null) {
            x += p.x
            y += p.y
            p = p.parent
        }
        return x.toFloat() to y.toFloat()
    }

    // ---------------------------------------------------------------------------
    // Edges
    // ---------------------------------------------------------------------------

    private fun buildEdgeRoutes(builder: ElkGraphBuilder): Map<EdgeId, EdgeRoute> {
        val result = mutableMapOf<EdgeId, EdgeRoute>()
        for ((edgeId, elkEdge) in builder.edgeIdToElkEdge) {
            val section = ElkGraphUtil.firstEdgeSection(elkEdge, false, false) ?: continue
            // V11.x — ELK reports edge section coordinates relative to the
            // edge's *containing node* (the LCA of source and target, set by
            // `ElkGraphUtil.updateContainment`). For edges between top-level
            // nodes the containing node is the root and the offset is (0, 0),
            // so the previous code happened to work. For edges between two
            // children of a compound (e.g. two UseCases inside the same
            // `UmlUseCaseSubject`), the containing node is the compound and
            // ELK's `startX/Y`, `endX/Y` and bend points are relative to it.
            // We translate to absolute canvas coordinates the same way
            // [absolutePosition] does for nodes.
            val containing = elkEdge.containingNode
            val (offX, offY) =
                if (containing != null) absolutePosition(containing) else 0f to 0f
            val source = Point(section.startX.toFloat() + offX, section.startY.toFloat() + offY)
            val target = Point(section.endX.toFloat() + offX, section.endY.toFloat() + offY)
            val bendPoints = section.bendPoints

            val route: EdgeRoute =
                if (bendPoints.isEmpty()) {
                    EdgeRoute.Direct(source = source, target = target)
                } else {
                    val waypoints =
                        bendPoints.map { Point(it.x.toFloat() + offX, it.y.toFloat() + offY) }
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

        // V11.x — Groups that opted in via `layoutAsCompound` get their bounds
        // straight from ELK. ELK has actually laid them out as compound nodes
        // (their members were placed under the compound in ElkGraphBuilder),
        // so `elkGroup.x/y/width/height` reflect a correct, member-fitted box.
        // Falling back to the bounding-box-from-members heuristic for those
        // groups would just re-derive almost the same box at extra cost — and
        // could disagree at the edges, breaking edge endpoints that ELK
        // anchored on the compound boundary.
        val compoundGroupIds: Set<GroupId> =
            builder
                .groups()
                .filter { it.layoutAsCompound }
                .map { it.id }
                .toSet()
        val result = mutableMapOf<GroupId, GroupLayout>()
        for (gid in compoundGroupIds) {
            val elkGroup = builder.groupMap[gid] ?: continue
            val (absX, absY) = absolutePosition(elkGroup)
            result[gid] =
                GroupLayout(
                    bounds = Rect(Point(absX, absY), Size(elkGroup.width.toFloat(), elkGroup.height.toFloat())),
                )
        }

        // Build: groupId → list of elk nodes that are members of that group.
        // The original LayoutNode has groupId; its ElkNode has the final position.
        val membersByGroup = mutableMapOf<GroupId, MutableList<org.eclipse.elk.graph.ElkNode>>()
        for (node in builder.nodes()) {
            val gid = node.groupId ?: continue
            if (gid in compoundGroupIds) continue // already covered above
            val elkNode = builder.nodeMap[node.id] ?: continue
            membersByGroup.getOrPut(gid) { mutableListOf() }.add(elkNode)
        }

        // V2.0.45 — collect LayoutGroup.padding for per-group bounds inflation.
        // The bridge uses this to reserve room for content that protrudes past
        // a member node's bounding box (e.g. SysML-2 pin labels on the left /
        // right of action boxes). Without this, the dashed partition outline
        // would clip through the pin label text.
        val paddingByGroup: Map<GroupId, Insets> =
            builder.groups().associate { it.id to it.padding }

        // Horizontal / vertical margin around the tightest bounding box of nodes.
        // V2.0.45 — reduced from 16f to 10f so vertically-/horizontally-adjacent
        // partitions have a visible gap between their dashed outlines.
        // Background: previous defaults (`headerPx=28, margin=16`) summed to a
        // total vertical inflation of `headerPx + 2*margin = 60`px per lane,
        // which is **exactly** ELK's default `layerSpacing` (60 px). Adjacent
        // vertically-stacked lanes therefore touched at the partition boundary
        // — the dashed Customer/OrderSystem/Warehouse outlines in the SysML-2
        // Order-Processing example rendered as one big rectangle with no
        // visual separation. Halving the surrounding margin restores a clearly
        // visible gap (≈16–20 px with default ELK spacings) while preserving
        // enough breathing room around member action boxes (10 px ≈ a comma's
        // worth of whitespace).
        val margin = 10f
        // Extra top space for the swimlane header bar — mirrors
        // [dev.kuml.io.svg.sysml2.PARTITION_HEADER_HEIGHT] **exactly** so the
        // header bar drawn by the SVG renderer fits flush into the reserved
        // top strip. V2.0.45 — was 28f, which was a leftover from an early
        // header-height tuning and contributed an unintended +4 px to the
        // top-side inflation. Aligning the two constants removes that drift.
        val headerPx = 24f

        for (groupId in builder.groupMap.keys) {
            if (groupId in compoundGroupIds) continue // compound: handled above
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

            // V2.0.45 — add per-group padding on top of the base margin. The
            // bridge sets this to reserve room for content that sits outside
            // the member nodes' bounding boxes (SysML-2 ACT: pin labels;
            // future: free-floating annotations, lane-internal callouts).
            val pad = paddingByGroup[groupId] ?: Insets.ZERO
            val ox = minX - margin - pad.left
            val oy = minY - headerPx - margin - pad.top
            val sw = (maxX - minX) + 2f * margin + pad.left + pad.right
            val sh = (maxY - minY) + headerPx + 2f * margin + pad.top + pad.bottom

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
     * um `headerPx + margin = 34px` (V2.0.45; vorher 44px) über das oberste
     * Mitgliedsknoten-Y hinaus nach **oben** zieht (Platz für die Header-Beschriftung der
     * Swimlane/System-Boundary), landeten Group-Origins im negativen Y. Die [Size]-basierte
     * viewBox `0 0 W H` der SVG-Ausgabe schnitt die Boundary-Oberkante dann ab. Hier wird
     * die Bounding-Box über alle drei Mengen gezogen und ggf. ein Translation-Pass
     * angewandt.
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
