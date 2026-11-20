@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
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
import dev.kuml.layout.bridge.erm.ErmContentSizeProvider.Companion as Sizing

/**
 * TikZ body of an ERM diagram in Martin (crow's-foot) notation: entity boxes with PK/FK rows, view boxes,
 * relationship lines with crow's-foot glyphs and notes. Node boxes and edge routes come from the same layout graph
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
    ) {
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
            val entity = entities[nodeId.value]
            if (entity != null) {
                renderEntity(entity = entity, nodeId = nodeId, layout = nodeLayout, diagram = diagram, options = options, out = out)
                continue
            }
            val view = views[nodeId.value] ?: continue
            renderView(view = view, nodeId = nodeId, layout = nodeLayout, options = options, out = out)
        }
        for (note in notes) NoteTikz.renderNote(spec = note, options = options, out = out)

        val linkIds = model.noteLinks.map { it.id }.toSet()
        for ((edgeId, route) in layout.edges) {
            if (edgeId.value in linkIds) {
                NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = out)
                continue
            }
            val rel = relationships[edgeId.value] ?: continue
            renderRelationship(rel = rel, route = route, options = options, out = out)
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
    ) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        val ind = options.indent
        frame(nodeId = nodeId, x = x, y = y, w = w, h = h, options = options, out = out, dashed = false)
        if (entity.weak) {
            val inset = WEAK_BORDER_INSET
            out.appendLine(
                "$ind\\draw[line width=0.6pt] ${pt(x + inset, y + inset)} rectangle ${pt(x + w - inset, y + h - inset)};",
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
    ) {
        val dash = if (dashed) ", dashed" else ""
        out.appendLine(
            "${options.indent}\\node[kuml-class$dash, anchor=north west, " +
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
        options: LatexRenderOptions,
        out: StringBuilder,
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
        CrowFootTikz.appendGlyph(
            anchor = route.source,
            dir = dirs.source,
            cardinality = rel.sourceCardinality,
            options = options,
            out = out,
        )
        CrowFootTikz.appendGlyph(
            anchor = route.target,
            dir = dirs.target,
            cardinality = rel.targetCardinality,
            options = options,
            out = out,
        )

        val name = rel.name?.takeIf { it.isNotBlank() }
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

    private fun pt(
        x: Float,
        y: Float,
    ): String = CrowFootTikz.pt(x, y)
}
