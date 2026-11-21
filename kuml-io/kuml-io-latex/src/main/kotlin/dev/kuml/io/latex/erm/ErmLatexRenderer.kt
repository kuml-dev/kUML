@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ErmView
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.note.NoteTikz
import dev.kuml.io.latex.note.NoteTikzAnchor
import dev.kuml.io.latex.note.NoteTikzSpec
import dev.kuml.io.latex.routeMidPoint
import dev.kuml.io.latex.tikzId
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.SelfLoopRouter
import dev.kuml.layout.bridge.SelfLoopSide
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.layout.bridge.erm.ErmContentSizeProvider.Companion as Sizing

/**
 * TikZ body of an ERM diagram in Martin (crow's-foot), Bachman or IDEF1X notation: entity boxes with PK/FK rows, view
 * boxes, relationship lines with the notation's glyphs, IDEF1X category circles and notes. Chen is not supported. Node boxes and edge routes come from the same layout graph
 * as the SVG output; row heights use the constants of the layout bridge's size provider (the same values the SVG
 * renderer mirrors), so rows stay inside the boxes. The wrapper (preamble, picture, styles, note placement) lives in
 * [dev.kuml.io.latex.KumlLatexRenderer].
 */
internal object ErmLatexRenderer {
    /** Writes the picture content for [model]; [layout] is the already note-placed layout. */
    fun renderBody(
        model: ErmModel,
        diagram: ErmDiagram,
        layout: LayoutResult,
        options: LatexRenderOptions,
        out: StringBuilder,
        notation: ErmNotation = ErmNotation.MARTIN,
    ) {
        val idef1x = notation == ErmNotation.IDEF1X
        val dependent = if (idef1x) Idef1xTikz.dependentEntityIds(model) else emptySet()
        val categories = model.categories.associateBy { it.id }
        val entities = model.entities.associateBy { it.id }
        val views = model.views.associateBy { it.id }
        val relationships = model.relationships.associateBy { it.id }
        val noteIds = model.notes.map { it.id }.toSet()
        val notes =
            model.notes.mapNotNull { n ->
                val node = layout.nodes[NodeId(n.id)] ?: return@mapNotNull null
                NoteTikzSpec(id = n.id, body = n.note.body, kind = n.note.kind, bounds = node.bounds)
            }
        if (notes.isNotEmpty()) {
            NoteTikz.appendNoteStyles(sb = out, indent = options.indent, kinds = notes.map { it.kind })
        }

        for ((nodeId, nodeLayout) in layout.nodes) {
            if (nodeId.value in noteIds) continue
            if (idef1x && nodeId.value.startsWith(ErmIdef1xLayoutBridge.CATEGORY_NODE_PREFIX)) {
                val category = categories[nodeId.value.removePrefix(ErmIdef1xLayoutBridge.CATEGORY_NODE_PREFIX)] ?: continue
                Idef1xTikz.appendCategory(
                    category = category,
                    nodeLayout = nodeLayout,
                    supertype = entities[category.supertypeEntityId],
                    options = options,
                    out = out,
                )
                continue
            }
            val entity = entities[nodeId.value]
            if (entity != null) {
                renderEntity(
                    entity = entity,
                    nodeId = nodeId,
                    layout = nodeLayout,
                    diagram = diagram,
                    options = options,
                    out = out,
                    cornerRadius = if (nodeId.value in dependent) Idef1xTikz.CORNER_RADIUS_PX else 0f,
                )
                continue
            }
            val view = views[nodeId.value] ?: continue
            renderView(view = view, nodeId = nodeId, layout = nodeLayout, options = options, out = out)
        }
        for (note in notes) NoteTikz.renderNote(spec = note, options = options, out = out)

        val linkIds = model.noteLinks.map { it.id }.toSet()
        // Nacharbeit 129: one self-loop side decision for the layout, the same planner the SVG renderer uses.
        val selfLoopPlan = SelfLoopRouter.planAll(layout = layout, elementLookup = relationships::get)
        for ((edgeId, route) in layout.edges) {
            if (edgeId.value in linkIds) {
                NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = out)
                continue
            }
            if (idef1x &&
                (
                    edgeId.value.startsWith(ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUP_PREFIX) ||
                        edgeId.value.startsWith(ErmIdef1xLayoutBridge.CATEGORY_EDGE_SUB_PREFIX)
                )
            ) {
                UmlEdgeLatexRenderer.render(
                    route = route,
                    style = UmlEdgeLatexRenderer.Style.PLAIN,
                    options = options,
                    out = out,
                    arrowOverride = "-",
                )
                continue
            }
            val rel = relationships[edgeId.value] ?: continue
            // Nacharbeit 127: a self-reference gets the same right-hand loop as in the SVG output instead of ELK's raw U-shape.
            val routed =
                SelfLoopRouter.adjust(
                    element = rel,
                    originalRoute = route,
                    nodeLookup = { id -> layout.nodes[NodeId(id)] },
                    plan = selfLoopPlan,
                    edgeId = edgeId.value,
                )
            val selfLoopNode = SelfLoopRouter.selfLoopNodeId(rel)?.let { layout.nodes[NodeId(it)] }
            renderRelationship(
                rel = rel,
                route = routed,
                notation = notation,
                options = options,
                out = out,
                selfLoopNode = selfLoopNode,
                selfLoopSide = SelfLoopRouter.sideOf(selfLoopPlan, edgeId.value),
            )
        }
        // A note link the layout carries no route for still gets a line between the two named nodes.
        for (link in model.noteLinks) {
            if (EdgeId(link.id) in layout.edges) continue
            val noteNode = NodeId(link.link.noteId)
            val target = NodeId(link.link.targetId)
            if (noteNode in layout.nodes && target in layout.nodes) {
                NoteTikz.renderAnchorBetween(noteId = noteNode.value, targetId = target.value, options = options, out = out)
            }
        }
    }

    private fun renderEntity(
        entity: ErmEntity,
        nodeId: NodeId,
        layout: NodeLayout,
        diagram: ErmDiagram,
        options: LatexRenderOptions,
        out: StringBuilder,
        cornerRadius: Float = 0f,
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        val ind = options.indent
        frame(nodeId = nodeId, x = x, y = y, w = w, h = h, options = options, out = out, dashed = false, cornerRadius = cornerRadius)
        if (entity.weak) {
            val inset = WEAK_BORDER_INSET
            val rounded = if (cornerRadius > 0f) ", rounded corners=${fmtCoord(Idef1xTikz.innerCornerRadiusPx)}pt" else ""
            out.appendLine(
                "$ind\\draw[line width=0.6pt$rounded] ${pt(x + inset, y + inset)} rectangle ${pt(x + w - inset, y + h - inset)};",
            )
        }
        out.appendLine(
            "$ind\\node[kuml-classname, anchor=center] at ${pt(x + w / 2f, y + Sizing.TITLE_ROW_H / 2f)} " +
                "{${escapeLatex(entity.name ?: entity.id)}};",
        )
        var cy = Sizing.TITLE_ROW_H
        val pk = entity.primaryKey
        val rest = entity.attributes.filterNot { it.primaryKey }
        if (pk.isNotEmpty()) {
            divider(x = x, y = y + cy, w = w, options = options, out = out)
            cy += Sizing.DIVIDER_GAP
            for (attr in pk) {
                row(attr = attr, marker = "PK", underline = true, x = x, y = y, cy = cy, w = w, options = options, out = out)
                cy += Sizing.ROW_H
            }
        }
        if (rest.isNotEmpty()) {
            divider(x = x, y = y + cy, w = w, options = options, out = out)
            cy += Sizing.DIVIDER_GAP
            for (attr in rest) {
                val marker =
                    when {
                        attr.foreignKey != null -> "FK"
                        attr.unique -> "U"
                        else -> ""
                    }
                row(attr = attr, marker = marker, underline = false, x = x, y = y, cy = cy, w = w, options = options, out = out)
                cy += Sizing.ROW_H
            }
        }
        if (diagram.showIndexes && (entity.indexes.isNotEmpty() || entity.checks.isNotEmpty())) {
            divider(x = x, y = y + cy, w = w, options = options, out = out)
            cy += Sizing.DIVIDER_GAP
            for (idx in entity.indexes) {
                val unique = if (idx.unique) " UNIQUE" else ""
                val label = "«idx» ${idx.name ?: idx.id} (${idx.attributeIds.joinToString(", ")})$unique"
                smallRow(text = label, x = x, y = y + cy, options = options, out = out)
                cy += Sizing.ROW_H
            }
            for (check in entity.checks) {
                smallRow(text = "«check» ${check.expression}", x = x, y = y + cy, options = options, out = out)
                cy += Sizing.ROW_H
            }
        }
    }

    private fun renderView(
        view: ErmView,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        frame(nodeId = nodeId, x = x, y = y, w = w, h = h, options = options, out = out, dashed = true)
        out.appendLine(
            "${options.indent}\\node[kuml-stereotype, anchor=center] at ${pt(x + w / 2f, y + Sizing.TITLE_ROW_H / 2f)} " +
                "{${escapeLatex("«view» ${view.name ?: view.id}")}};",
        )
        divider(x = x, y = y + Sizing.TITLE_ROW_H, w = w, options = options, out = out)
        val preview = view.query.replace("\n", " ").trim()
        val maxChars = ((w - 2 * Sizing.PAD_X) / Sizing.SMALL_CHAR_PX).toInt().coerceAtLeast(4)
        val truncated = if (preview.length > maxChars) preview.take(maxChars - 1) + "…" else preview
        smallRow(text = truncated, x = x, y = y + Sizing.TITLE_ROW_H + Sizing.DIVIDER_GAP, options = options, out = out)
    }

    private fun frame(
        nodeId: NodeId,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
        dashed: Boolean,
        cornerRadius: Float = 0f,
    ) {
        val dash = if (dashed) ", dashed" else ""
        val rounded = if (cornerRadius > 0f) ", rounded corners=${fmtCoord(cornerRadius)}pt" else ""
        out.appendLine(
            "${options.indent}\\node[kuml-class$dash$rounded, anchor=north west, " +
                "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                "(${tikzId(nodeId)}) at ${pt(x, y)} {};",
        )
    }

    private fun divider(
        x: Float,
        y: Float,
        w: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine("${options.indent}\\draw[line width=0.4pt] ${pt(x, y)} -- ${pt(x + w, y)};")
    }

    private fun smallRow(
        text: String,
        x: Float,
        y: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine(
            "${options.indent}\\node[kuml-feature, font=\\sffamily\\scriptsize\\itshape, anchor=base west] " +
                "at ${pt(x + Sizing.PAD_X, y)} {${escapeLatex(text)}};",
        )
    }

    @Suppress("LongParameterList")
    private fun row(
        attr: ErmAttribute,
        marker: String,
        underline: Boolean,
        x: Float,
        y: Float,
        cy: Float,
        w: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val ind = options.indent
        if (marker.isNotEmpty()) {
            out.appendLine(
                "$ind\\node[kuml-feature, font=\\sffamily\\scriptsize\\bfseries, anchor=base west] at ${pt(x + 4f, y + cy)} {$marker};",
            )
        }
        val name = escapeLatex(attr.name ?: attr.id)
        val shownName = if (underline) "\\underline{$name}" else name
        val suffix = if (attr.nullable) "" else " NN"
        out.appendLine(
            "$ind\\node[kuml-feature, anchor=base west] at ${pt(x + Sizing.MARKER_COL_W, y + cy)} " +
                "{$shownName : ${escapeLatex(attr.type.render())}${escapeLatex(suffix)}};",
        )
        val default = attr.default
        if (default != null) {
            out.appendLine(
                "$ind\\node[kuml-feature, font=\\sffamily\\scriptsize\\itshape, anchor=base east] " +
                    "at ${pt(x + w - Sizing.PAD_X, y + cy)} {${escapeLatex("= $default")}};",
            )
        }
    }

    private fun renderRelationship(
        rel: ErmRelationship,
        route: EdgeRoute,
        notation: ErmNotation,
        options: LatexRenderOptions,
        out: StringBuilder,
        selfLoopNode: NodeLayout? = null,
        selfLoopSide: SelfLoopSide = SelfLoopSide.RIGHT,
    ) {
        val dashed = rel.kind == RelationshipKind.NON_IDENTIFYING
        UmlEdgeLatexRenderer.render(
            route = route,
            style = UmlEdgeLatexRenderer.Style.PLAIN,
            options = options,
            out = out,
            arrowOverride = if (dashed) "dashed, -" else "-",
        )
        val dirs = CrowFootTikz.endDirections(route)
        when (notation) {
            ErmNotation.MARTIN -> {
                CrowFootTikz.appendGlyph(route.source, dirs.source, rel.sourceCardinality, options, out)
                CrowFootTikz.appendGlyph(route.target, dirs.target, rel.targetCardinality, options, out)
            }
            ErmNotation.BACHMAN -> {
                BachmanTikz.appendGlyph(route.source, dirs.source, rel.sourceCardinality, options, out)
                BachmanTikz.appendGlyph(route.target, dirs.target, rel.targetCardinality, options, out)
            }
            ErmNotation.IDEF1X -> {
                if (rel.sourceCardinality.optional) Idef1xTikz.appendParentDiamond(route.source, dirs.source, options, out)
                Idef1xTikz.appendChildDot(route.target, dirs.target, options, out)
                Idef1xTikz.cardinalityLabel(rel.targetCardinality)?.let {
                    Idef1xTikz.appendCardinalityLabel(route.target, dirs.target, it, options, out)
                }
            }
            else -> error("unreachable: Chen is rejected before rendering")
        }

        val name = rel.name?.takeIf { it.isNotBlank() }
        if (selfLoopNode != null && route is EdgeRoute.OrthogonalRounded && route.waypoints.isNotEmpty()) {
            appendSelfLoopLabels(
                rel = rel,
                name = name,
                route = route,
                node = selfLoopNode,
                side = selfLoopSide,
                options = options,
                out = out,
            )
            return
        }
        if (name != null) {
            val mid = routeMidPoint(route)
            if (mid != null) {
                out.appendLine(
                    "${options.indent}\\node[kuml-feature, fill=white, inner sep=1pt, anchor=south] " +
                        "at ${pt(mid.first, mid.second)} {${escapeLatex(name)}};",
                )
            }
        }
        roleLabel(role = rel.sourceRole, anchor = route.source, dir = dirs.source, options = options, out = out)
        roleLabel(role = rel.targetRole, anchor = route.target, dir = dirs.target, options = options, out = out)
    }

    /**
     * Labels of a self-reference (Nacharbeit 127), laid out for the right-hand loop of [SelfLoopRouter]: the name to the
     * right of the loop (no background, so the loop line stays uninterrupted), the source role above and the target role
     * below the loop ends, both just outside the entity box. All y values are layout space (y down); [pt] flips them.
     */
    private fun appendSelfLoopLabels(
        rel: ErmRelationship,
        name: String?,
        route: EdgeRoute.OrthogonalRounded,
        node: NodeLayout,
        side: SelfLoopSide = SelfLoopSide.RIGHT,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val ind = options.indent
        val left = side == SelfLoopSide.LEFT
        val southAnchor = if (left) "anchor=south east" else "anchor=south west"
        val northAnchor = if (left) "anchor=north east" else "anchor=north west"
        val borderX = if (left) node.bounds.origin.x else node.bounds.origin.x + node.bounds.size.width
        val outerX = route.waypoints[0].x
        if (name != null) {
            val cy = node.bounds.origin.y + node.bounds.size.height / 2f
            out.appendLine(
                "$ind\\node[kuml-feature, inner sep=1pt, ${if (left) "anchor=east" else "anchor=west"}] at " +
                    "${pt(if (left) outerX - SELF_LOOP_NAME_GAP else outerX + SELF_LOOP_NAME_GAP, cy)} {${escapeLatex(name)}};",
            )
        }
        rel.sourceRole?.takeIf { it.isNotBlank() }?.let {
            out.appendLine(
                "$ind\\node[kuml-feature, font=\\sffamily\\scriptsize, fill=white, inner sep=0.5pt, $southAnchor] " +
                    "at ${pt(roleX(borderX, left), route.source.y - SELF_LOOP_ROLE_GAP_Y)} {${escapeLatex(it)}};",
            )
        }
        rel.targetRole?.takeIf { it.isNotBlank() }?.let {
            out.appendLine(
                "$ind\\node[kuml-feature, font=\\sffamily\\scriptsize, fill=white, inner sep=0.5pt, $northAnchor] " +
                    "at ${pt(roleX(borderX, left), route.target.y + SELF_LOOP_ROLE_GAP_Y)} {${escapeLatex(it)}};",
            )
        }
    }

    private fun roleX(
        borderX: Float,
        left: Boolean,
    ): Float = if (left) borderX - SELF_LOOP_ROLE_INSET_X else borderX + SELF_LOOP_ROLE_INSET_X

    private fun roleLabel(
        role: String?,
        anchor: dev.kuml.layout.Point,
        dir: Pair<Float, Float>,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val text = role?.takeIf { it.isNotBlank() } ?: return
        val x = anchor.x + dir.first * ROLE_ALONG + (-dir.second) * ROLE_ASIDE
        val y = anchor.y + dir.second * ROLE_ALONG + dir.first * ROLE_ASIDE
        out.appendLine(
            "${options.indent}\\node[kuml-feature, font=\\sffamily\\scriptsize, fill=white, inner sep=0.5pt] " +
                "at ${pt(x, y)} {${escapeLatex(text)}};",
        )
    }

    private const val WEAK_BORDER_INSET: Float = 3f
    private const val ROLE_ALONG: Float = 30f
    private const val ROLE_ASIDE: Float = 9f
    private const val SELF_LOOP_NAME_GAP: Float = 4f
    private const val SELF_LOOP_ROLE_INSET_X: Float = 2f
    private const val SELF_LOOP_ROLE_GAP_Y: Float = 10f

    private fun pt(
        x: Float,
        y: Float,
    ): String = CrowFootTikz.pt(x, y)
}
