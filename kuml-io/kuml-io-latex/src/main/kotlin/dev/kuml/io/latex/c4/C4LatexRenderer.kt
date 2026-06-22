package dev.kuml.io.latex.c4

import dev.kuml.c4.model.C4CodeElement
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.tikzId
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point

/**
 * Renders C4 model elements as TikZ nodes and edges.
 *
 * Each C4 element type maps to a dedicated TikZ node style:
 * - [C4Person] → `kuml-c4-person` (rounded rectangle, «person» / «external person»)
 * - [C4SoftwareSystem] → `kuml-c4-system` (rectangle, «software system» / «external software system»)
 * - [C4Container] → `kuml-c4-container` (rectangle, «container[: technology]»)
 * - [C4Component] → `kuml-c4-component` (rectangle, «component[: technology]»)
 * - [C4DeploymentNode] → `kuml-c4-node` (dashed rectangle, «node[: technology]»)
 * - [C4CodeElement] → `kuml-c4-code` (rectangle, «code element[: technology]»)
 *
 * Coordinate system: layout uses Y-down (CSS convention), TikZ uses Y-up
 * (cartesian). We flip by negating every Y on emission — `(x, -y)pt` — so
 * a layout point `(100, 50)` becomes `(100pt, -50pt)`. This matches the
 * Y-flip convention used throughout [dev.kuml.io.latex.KumlLatexRenderer].
 *
 * Each node is rendered as a two-part overlay:
 *  1. An outer frame rectangle at the layout bounds (anchor: north west).
 *  2. A centred label node with stereotype above and name below, using a
 *     LaTeX `tabular` the same way [dev.kuml.io.latex.uml.UmlClassLatexRenderer]
 *     handles classifier headers.
 *
 * An optional description line is appended below the name when present.
 */
internal object C4LatexRenderer {
    // ─── Node dispatch ────────────────────────────────────────────────────────

    fun renderNode(
        element: C4Element,
        nodeId: NodeId,
        nodeLayout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        // Assigned to a Unit variable so that the compiler enforces exhaustiveness:
        // adding a new C4Element subtype without a branch here will be a compile error.
        @Suppress("RedundantUnitExpression")
        val ignored: Unit =
            when (element) {
                is C4Person -> renderPerson(element, nodeId, nodeLayout, options, out)
                is C4SoftwareSystem -> renderSoftwareSystem(element, nodeId, nodeLayout, options, out)
                is C4Container -> renderContainer(element, nodeId, nodeLayout, options, out)
                is C4Component -> renderComponent(element, nodeId, nodeLayout, options, out)
                is C4DeploymentNode -> renderDeploymentNode(element, nodeId, nodeLayout, options, out)
                is C4CodeElement -> renderCodeElement(element, nodeId, nodeLayout, options, out)
                // C4Relationship and C4Model are structural/relational elements — not renderable as nodes.
                is C4Relationship -> Unit
                is C4Model -> Unit
            }
    }

    // ─── Element-specific renders ─────────────────────────────────────────────

    private fun renderPerson(
        element: C4Person,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val stereotypeBase = if (element.external) "external person" else "person"
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-person",
            stereotypeBase = stereotypeBase,
            technology = null,
            name = element.name,
            detail = null,
            description = element.description,
        )
    }

    private fun renderSoftwareSystem(
        element: C4SoftwareSystem,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val stereotypeBase = if (element.external) "external software system" else "software system"
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-system",
            stereotypeBase = stereotypeBase,
            technology = null,
            name = element.name,
            detail = null,
            description = element.description,
        )
    }

    private fun renderContainer(
        element: C4Container,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-container",
            stereotypeBase = "container",
            technology = element.technology,
            name = element.name,
            detail = null,
            description = element.description,
        )
    }

    private fun renderComponent(
        element: C4Component,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-component",
            stereotypeBase = "component",
            technology = element.technology,
            name = element.name,
            detail = null,
            description = element.description,
        )
    }

    private fun renderDeploymentNode(
        element: C4DeploymentNode,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val detail = if (element.instances > 1) "\$\\times\$${element.instances}" else null
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-node",
            stereotypeBase = "node",
            technology = element.technology,
            name = element.name,
            detail = detail,
            description = element.description,
        )
    }

    private fun renderCodeElement(
        element: C4CodeElement,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        renderBox(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            style = "kuml-c4-code",
            stereotypeBase = "code element",
            technology = element.technology,
            name = element.name,
            detail = null,
            description = element.description,
        )
    }

    // ─── Generic box emitter ──────────────────────────────────────────────────

    /** Maximum characters allowed in a description before truncation with an ellipsis. */
    private const val DESCRIPTION_MAX_CHARS = 200

    /**
     * Emits the outer frame + centered label node for any C4 element.
     *
     * The outer frame is an anchor=north west rectangle at the layout bounds.
     * The label is a centered `\node` with a `tabular` wrapping stereotype,
     * name, optional detail line, and optional description — all in the same
     * visual center of the box.
     *
     * @param style TikZ style name (must be defined in the preamble via `\tikzset`).
     * @param stereotypeBase Plain-text stereotype label without technology suffix
     *   (e.g. "person", "container"). Must be a hardcoded literal — never user input.
     *   The function appends `: <escaped technology>` when [technology] is non-null,
     *   escaping the technology string internally.
     * @param technology Optional raw (unescaped) technology string supplied by the
     *   model author. Escaped by this function via [escapeLatex] before emission.
     * @param name Element name shown in bold (will be LaTeX-escaped here).
     * @param detail Optional pre-formed LaTeX fragment between name and description
     *   (e.g. `$\times$3`). Emitted verbatim — callers are responsible for LaTeX safety.
     * @param description Optional description shown in small font below the name
     *   (will be LaTeX-escaped here). Truncated to [DESCRIPTION_MAX_CHARS] characters
     *   with an ellipsis to prevent TikZ canvas overflow from extremely long strings.
     */
    @Suppress("LongParameterList")
    private fun renderBox(
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
        style: String,
        stereotypeBase: String,
        technology: String?,
        name: String,
        detail: String?,
        description: String?,
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        val tikzNodeId = tikzId(nodeId)

        // Outer frame — anchor north west so the box top-left aligns with the
        // layout origin (which is also Y-down top-left).
        out.appendLine(
            "${options.indent}\\node[$style, anchor=north west, " +
                "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                "($tikzNodeId) at (${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {};",
        )

        // Build the stereotype string: "base" or "base: escaped-technology".
        // stereotypeBase is always a hardcoded literal (safe); technology is
        // user-supplied and escaped here — never concatenated before escaping.
        val stereotype =
            if (technology != null) "$stereotypeBase: ${escapeLatex(technology)}" else stereotypeBase

        // Label — stereotype + name + optional lines inside a tabular so
        // vertical centering is one anchor instead of multiple stacked nodes.
        // `detail` is a pre-formed LaTeX fragment; `name`, `technology`, and
        // `description` are user-supplied strings that are escaped above / here.
        val stereoLine = "\\textit{\\small \\guillemotleft{}$stereotype\\guillemotright{}}"
        val nameLine = "\\textbf{${escapeLatex(name)}}"

        // Truncate description to prevent multi-kilobyte strings from overflowing
        // the TikZ canvas and causing pdflatex to run extremely slowly.
        val safeDescription =
            description?.let { raw ->
                val truncated =
                    if (raw.length > DESCRIPTION_MAX_CHARS) raw.take(DESCRIPTION_MAX_CHARS) + "…" else raw
                escapeLatex(truncated)
            }

        val rows =
            buildList {
                add(stereoLine)
                add(nameLine)
                if (detail != null) add("\\small $detail") // pre-formed LaTeX, emitted verbatim
                // Wrap in \parbox so very long (but within the char cap) descriptions
                // word-wrap inside the node rather than overflowing the canvas edge.
                if (safeDescription != null) {
                    add("\\small \\parbox{${fmtCoord(w * 0.85f)}pt}{\\centering $safeDescription}")
                }
            }
        val tabularContent = rows.joinToString("\\\\")
        val label = "\\begin{tabular}{c}$tabularContent\\end{tabular}"

        out.appendLine(
            "${options.indent}\\node[kuml-c4-label, anchor=center] " +
                "at ($tikzNodeId.center) {$label};",
        )
    }

    // ─── Edge renderer ────────────────────────────────────────────────────────

    /**
     * Renders all C4 relationships visible in [layoutResult] as TikZ draw paths.
     *
     * Relationships are looked up by [C4Relationship.id] in [relationships].
     * Any edge present in [layoutResult.edges] that has no matching relationship
     * falls back to a plain solid arrow (label-less) — consistent with the UML
     * edge fallback.
     */
    fun renderEdges(
        relationships: List<C4Relationship>,
        layoutResult: LayoutResult,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val relById = relationships.associateBy { it.id }
        for ((edgeId, route) in layoutResult.edges) {
            val rel = relById[edgeId.value]
            renderEdge(rel, route, edgeId, options, out)
        }
    }

    private fun renderEdge(
        relationship: C4Relationship?,
        route: EdgeRoute,
        @Suppress("UnusedParameter") edgeId: EdgeId,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        // Bidirectional relationships get a dual-arrowhead style; others get
        // a single forward arrow (PLAIN).
        val style =
            if (relationship?.bidirectional == true) {
                UmlEdgeLatexRenderer.Style.BIDIRECTIONAL
            } else {
                UmlEdgeLatexRenderer.Style.PLAIN
            }
        UmlEdgeLatexRenderer.render(route, style, options, out)

        // Build the edge label: "label [technology]" when both are present,
        // just "label" or "[technology]" when only one is set, nothing otherwise.
        val rawLabel = relationship?.label?.takeIf { it.isNotBlank() }
        val rawTech = relationship?.technology?.takeIf { it.isNotBlank() }
        val compositeLabel =
            when {
                rawLabel != null && rawTech != null -> "$rawLabel [$rawTech]"
                rawLabel != null -> rawLabel
                rawTech != null -> "[$rawTech]"
                else -> null
            }
        val mid = compositeLabel?.let { routeMidPoint(route) } ?: return
        out.appendLine(
            "${options.indent}\\node[kuml-c4-edge-label, anchor=center] " +
                "at (${fmtCoord(mid.first)}pt, ${fmtCoord(-mid.second)}pt) " +
                "{\\small ${escapeLatex(compositeLabel)}};",
        )
    }

    /** Returns the mid-point of the edge route in layout coordinates, or null when empty. */
    private fun routeMidPoint(route: EdgeRoute): Pair<Float, Float>? =
        when (route) {
            is EdgeRoute.Direct -> {
                val mx = (route.source.x + route.target.x) / 2f
                val my = (route.source.y + route.target.y) / 2f
                mx to my
            }
            is EdgeRoute.OrthogonalRounded ->
                midOfPolyline(route.source, route.waypoints, route.target)
            is EdgeRoute.TreeRounded ->
                midOfPolyline(route.source, route.waypoints, route.target)
            is EdgeRoute.Bezier -> {
                val mx = (route.source.x + route.target.x) / 2f
                val my = (route.source.y + route.target.y) / 2f
                mx to my
            }
        }

    /**
     * Returns the mid-point of a polyline defined by [source], [waypoints], and [target],
     * or null when the polyline has fewer than two points.
     *
     * The mid-point is the average of the two points flanking the centre index of the
     * assembled point list `[source] + waypoints + [target]`.
     */
    private fun midOfPolyline(
        source: Point,
        waypoints: List<Point>,
        target: Point,
    ): Pair<Float, Float>? {
        val allPoints =
            buildList {
                add(source)
                addAll(waypoints)
                add(target)
            }
        if (allPoints.size < 2) return null
        val mid = allPoints.size / 2
        val a = allPoints[mid - 1]
        val b = allPoints[mid]
        return (a.x + b.x) / 2f to (a.y + b.y) / 2f
    }

}
