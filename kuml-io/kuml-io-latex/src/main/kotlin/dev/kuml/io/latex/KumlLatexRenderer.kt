package dev.kuml.io.latex

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.io.latex.uml.UmlClassLatexRenderer
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Renders a kUML diagram + layout result as a LaTeX/TikZ source string.
 *
 * **V2.0.2 MVP scope**: class diagrams in the `plain` theme, snippet by
 * default (or standalone via [LatexRenderOptions.standalone]). Other diagram
 * types render as plain rectangles with a centred label — the structural
 * fidelity for state machines / use cases / C4 lands in a later V2.x wave
 * (see [[kUML V2.0]]).
 *
 * Coordinate system: layout uses pixels with **y down** (CSS convention),
 * TikZ uses cartesian coords with **y up**. We flip on the way out by
 * negating every Y, so a layout point `(100, 50)` becomes
 * `(100pt, -50pt)`. Text labels remain upright because we only flip the
 * coordinate axis on emission, not the picture-wide transform.
 *
 * The output is **layout-faithful**: every node lands at the exact pixel
 * coordinates the kUML layout engine produced, scaled by
 * [LatexRenderOptions.scale]. The TikZ output and the SVG output of the
 * same diagram are pixel-identical (modulo font-metric differences in the
 * rendered PDF).
 *
 * Usage:
 * ```kotlin
 * val tex = KumlLatexRenderer.toLatex(diagram, layout)
 * File("diagram.tex").writeText(tex)
 * ```
 *
 * @see LatexRenderOptions
 */
public object KumlLatexRenderer {
    /**
     * Render the diagram as TikZ source.
     *
     * @param diagram The kUML diagram. Elements not yet supported in the
     *   MVP (state machines, use cases, etc.) fall back to a labelled
     *   rectangle.
     * @param layoutResult Positions and edge routes from a kUML layout engine.
     *   Only nodes/edges present in this result get rendered — any extra
     *   diagram elements are silently skipped (matches the SVG renderer's
     *   behaviour).
     * @param options Tuning knobs; defaults are usually fine.
     * @return A self-contained `\begin{tikzpicture}…\end{tikzpicture}`
     *   block (snippet mode) or a complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)

            // Nodes — index elements by id once, then iterate the layout to
            // preserve the engine's deterministic ordering.
            val nodesById: Map<String, KumlElement> = diagram.elements.associateBy { it.id }

            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = nodesById[nodeId.value] ?: continue
                renderNode(element, nodeId, nodeLayout, options)
            }

            // Edges
            val relationshipsById: Map<String, UmlRelationship> =
                diagram.elements.filterIsInstance<UmlRelationship>().associateBy { it.id }

            for ((edgeId, route) in layoutResult.edges) {
                val rel = relationshipsById[edgeId.value]
                renderEdge(rel, route, options)
            }

            appendPictureClose()

            if (options.standalone) {
                appendStandaloneCoda()
            }
        }

    // ─── Node dispatch ────────────────────────────────────────────────────────

    private fun StringBuilder.renderNode(
        element: KumlElement,
        nodeId: NodeId,
        nodeLayout: dev.kuml.layout.NodeLayout,
        options: LatexRenderOptions,
    ) {
        when (element) {
            is UmlClassifier -> UmlClassLatexRenderer.render(element, nodeId, nodeLayout, options, this)
            is UmlNamedElement -> UmlClassLatexRenderer.renderFallback(nodeId, nodeLayout, options, this, label = element.name)
            else -> UmlClassLatexRenderer.renderFallback(nodeId, nodeLayout, options, this, label = element.id)
        }
    }

    // ─── Edge dispatch ────────────────────────────────────────────────────────

    private fun StringBuilder.renderEdge(
        relationship: UmlRelationship?,
        route: EdgeRoute,
        options: LatexRenderOptions,
    ) {
        // Style picked per UML relationship kind. Unknown / missing relationships
        // get a neutral solid line — that's still useful diagnostically (the
        // layout said "there's an edge here", we just don't know what UML calls it).
        val style =
            when (relationship) {
                is UmlGeneralization -> UmlEdgeLatexRenderer.Style.GENERALIZATION
                is UmlInterfaceRealization -> UmlEdgeLatexRenderer.Style.REALIZATION
                is UmlDependency -> UmlEdgeLatexRenderer.Style.DEPENDENCY
                is UmlAssociation -> UmlEdgeLatexRenderer.Style.ASSOCIATION
                else -> UmlEdgeLatexRenderer.Style.PLAIN
            }
        UmlEdgeLatexRenderer.render(route, style, options, this)
    }

    // ─── Preamble + skeleton ─────────────────────────────────────────────────

    private fun StringBuilder.appendStandalonePreamble() {
        appendLine("\\documentclass[border=10pt]{standalone}")
        appendLine("\\usepackage{tikz}")
        // `babel` provides `\guillemotleft` / `\guillemotright` reliably in
        // every modern engine; we don't need fontenc for ASCII output.
        appendLine("\\usepackage[T1]{fontenc}")
        appendLine("\\usepackage[english]{babel}")
        appendLine("\\usetikzlibrary{arrows.meta, calc, positioning}")
        appendLine("\\begin{document}")
    }

    private fun StringBuilder.appendStandaloneCoda() {
        appendLine("\\end{document}")
    }

    private fun StringBuilder.appendPictureOpen(scale: Double) {
        // `x=1pt, y=-1pt` would also work for axis-flipping but it inverts
        // arrow heads' implicit "up" direction in some libraries. We instead
        // negate Y at emission time and leave the global transform vanilla
        // except for an optional `scale=…`.
        if (scale == 1.0) {
            appendLine("\\begin{tikzpicture}")
        } else {
            appendLine("\\begin{tikzpicture}[scale=${fmtCoord(scale.toFloat())}, every node/.style={transform shape}]")
        }
    }

    private fun StringBuilder.appendPictureClose() {
        appendLine("\\end{tikzpicture}")
    }

    /**
     * Theme-style block emitted once per picture. The MVP `plain` theme is
     * monochrome: thin black borders, white background, sans-serif content.
     *
     * Users can override these styles in their document preamble (TikZ
     * resolves `/.style` lookups at parse time, so a later definition wins).
     */
    private fun StringBuilder.appendTikzStyles(indent: String) {
        appendLine("$indent% kUML plain theme — override these styles in your preamble to restyle.")
        appendLine("$indent\\tikzset{")
        appendLine("$indent  kuml-class/.style={draw=black, fill=white, line width=0.6pt, rectangle, inner sep=0pt, font=\\sffamily},")
        appendLine("$indent  kuml-interface/.style={kuml-class},")
        appendLine("$indent  kuml-enum/.style={kuml-class},")
        appendLine("$indent  kuml-stereotype/.style={font=\\sffamily\\itshape\\small},")
        appendLine("$indent  kuml-classname/.style={font=\\sffamily\\bfseries},")
        appendLine("$indent  kuml-classname-abstract/.style={font=\\sffamily\\bfseries\\itshape},")
        appendLine("$indent  kuml-feature/.style={font=\\sffamily\\footnotesize},")
        appendLine("$indent  kuml-association/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},")
        appendLine("$indent  kuml-generalization/.style={draw=black, line width=0.6pt, -{Triangle[length=3mm, open]}},")
        appendLine("$indent  kuml-realization/.style={draw=black, line width=0.6pt, dashed, -{Triangle[length=3mm, open]}},")
        appendLine("$indent  kuml-dependency/.style={draw=black, line width=0.5pt, dashed, -{Stealth[length=2mm]}},")
        appendLine("$indent  kuml-edge-plain/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},")
        appendLine("$indent}")
    }
}
