package dev.kuml.io.latex.sysml2.edge

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata

/**
 * Renders a single SysML 2 edge — TikZ line + optional dashed pattern,
 * arrow head and stereotype / plain label — into the parent `tikzpicture`.
 *
 * V2.0.13 dispatches every UC / REQ / STM / ACT / PAR edge through this
 * single path. The metadata is produced by a
 * `dev.kuml.sysml2.edge.Sysml2EdgeAdapter`; route geometry comes from the
 * [EdgeRoute] returned by the layout engine, with the Y-axis flipped on
 * emission (the LaTeX renderer's standard convention — see
 * `dev.kuml.io.latex.uml.UmlEdgeLatexRenderer`).
 *
 *  - Line style: picked from a small palette of pre-declared TikZ styles
 *    (`kuml-sysml2-edge-solid`, `kuml-sysml2-edge-dashed`,
 *    `kuml-sysml2-edge-binding`) declared by
 *    `KumlLatexRenderer.appendTikzStyles`. The arrow tip is appended per
 *    edge via `-{Stealth}` or `-{Latex}` depending on
 *    [Sysml2EdgeMetadata.arrowHead].
 *  - Labels: emitted as TikZ `node[midway, above=1pt, fill=white, inner sep=1.5pt]{…}` clauses on the line
 *    draw. Stereotype renders italic via the inline `\textit{…}` wrapping;
 *    plain labels stay upright. Two labels stack vertically using two
 *    `node[…, above=1pt, yshift=11pt, …]{…}` clauses.
 *
 * This is the parallel of `dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer`
 * — the two share the same metadata shape but the SVG side reuses CSS +
 * `<marker>` defs while the TikZ side composes the style attributes inline.
 */
internal object Sysml2EdgeLatexRenderer {
    /**
     * Draw a SysML 2 edge.
     *
     * @param route Route from the layout engine; emission flips Y per the
     *   LaTeX-renderer convention.
     * @param metadata Metadata produced by the matching adapter.
     * @param options Renderer options — used for indentation and coordinate
     *   formatting.
     * @param out Accumulating LaTeX source buffer.
     */
    fun render(
        route: EdgeRoute,
        metadata: Sysml2EdgeMetadata,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val style = pickStyle(metadata)
        renderLine(route = route, metadata = metadata, style = style, options = options, out = out)
        // Role name / multiplicity at the target end (the SVG draws it there as well).
        metadata.endLabel?.takeIf { it.isNotEmpty() }?.let { renderEndLabel(route = route, text = it, options = options, out = out) }
    }

    private fun renderLine(
        route: EdgeRoute,
        metadata: Sysml2EdgeMetadata,
        style: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        when (route) {
            is EdgeRoute.Direct -> renderDirect(route = route, metadata = metadata, style = style, options = options, out = out)
            is EdgeRoute.OrthogonalRounded ->
                renderPolyline(
                    source = route.source,
                    target = route.target,
                    waypoints = route.waypoints,
                    cornerRadiusPx = route.cornerRadiusPx,
                    metadata = metadata,
                    style = style,
                    options = options,
                    out = out,
                )
            is EdgeRoute.TreeRounded ->
                renderPolyline(
                    source = route.source,
                    target = route.target,
                    waypoints = route.waypoints,
                    cornerRadiusPx = route.cornerRadiusPx,
                    metadata = metadata,
                    style = style,
                    options = options,
                    out = out,
                )
            is EdgeRoute.Bezier -> renderBezier(route = route, metadata = metadata, style = style, options = options, out = out)
        }
    }

    /**
     * The end label sits [END_LABEL_OFFSET_PX] behind the target end along the last segment, shifted like in the SVG
     * ([dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer]): 6 px to the right and just beside the line.
     */
    private fun renderEndLabel(
        route: EdgeRoute,
        text: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val tip = route.target
        val prev =
            when (route) {
                is EdgeRoute.Direct -> route.source
                is EdgeRoute.OrthogonalRounded -> route.waypoints.lastOrNull() ?: route.source
                is EdgeRoute.TreeRounded -> route.waypoints.lastOrNull() ?: route.source
                is EdgeRoute.Bezier -> route.controlPoints.lastOrNull() ?: route.source
            }
        val dx = prev.x - tip.x
        val dy = prev.y - tip.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0.001f } ?: 1f
        val x = tip.x + dx / len * END_LABEL_OFFSET_PX + 6f
        val y = tip.y + dy / len * END_LABEL_OFFSET_PX + (if (dy > 0f) 10f else -2f)
        out.appendLine(
            "${options.indent}\\node[anchor=base west, inner sep=0pt, font=\\footnotesize] at " +
                "(${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {${escapeLatex(text)}};",
        )
    }

    private fun renderDirect(
        route: EdgeRoute.Direct,
        metadata: Sysml2EdgeMetadata,
        style: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine(
            "${options.indent}\\draw[$style] ${pt(route.source)} -- " +
                "${labelNodes(metadata)}${pt(route.target)};",
        )
    }

    private fun renderPolyline(
        source: Point,
        target: Point,
        waypoints: List<Point>,
        cornerRadiusPx: Float,
        metadata: Sysml2EdgeMetadata,
        style: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val cornerOpt = if (cornerRadiusPx > 0f) ", rounded corners=${fmtCoord(cornerRadiusPx)}pt" else ""
        val segments =
            buildString {
                append(pt(source))
                for (w in waypoints) {
                    append(" -- ")
                    append(pt(w))
                }
                append(" -- ")
                // Labels are anchored to the **last** segment (closest to the
                // target) so they sit near the arrow head — the convention
                // every existing TikZ tutorial uses for transition labels.
                append(labelNodes(metadata))
                append(pt(target))
            }
        out.appendLine("${options.indent}\\draw[$style$cornerOpt] $segments;")
    }

    private fun renderBezier(
        route: EdgeRoute.Bezier,
        metadata: Sysml2EdgeMetadata,
        style: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val c1 = route.controlPoints.getOrNull(0) ?: route.source
        val c2 = route.controlPoints.getOrNull(1) ?: route.target
        out.appendLine(
            "${options.indent}\\draw[$style] ${pt(route.source)} .. controls " +
                "${pt(c1)} and ${pt(c2)} .. ${labelNodes(metadata)}${pt(route.target)};",
        )
    }

    /**
     * Pick the TikZ edge style name based on the metadata.
     *
     * The five SysML 2 edge style classes are pre-declared by
     * `KumlLatexRenderer.appendTikzStyles`:
     *  - `kuml-sysml2-edge-solid` — solid, `-{Stealth}` arrow.
     *  - `kuml-sysml2-edge-dashed` — dashed, `-{Stealth}` arrow.
     *  - `kuml-sysml2-edge-binding` — solid, **no** arrow head (PAR).
     *
     * Other arrow-head shapes ([Sysml2ArrowHead.OpenTriangle]) are not yet
     * needed by the V2.0.13 wave; they would land in `kuml-sysml2-edge-
     * generalization`-style entries when the BDD-specialisation polish wave
     * arrives.
     */
    private fun pickStyle(metadata: Sysml2EdgeMetadata): String {
        // Welle B: diamonds (composition / aggregation). Frame and mid-arrows exist in SVG only.
        if (metadata.arrowHead == Sysml2ArrowHead.FilledDiamond) return "kuml-sysml2-edge-diamond-filled"
        if (metadata.arrowHead == Sysml2ArrowHead.OpenDiamond) return "kuml-sysml2-edge-diamond-open"
        // Wave 2E-a: requirement containment - solid line, circle at the owning end.
        if (metadata.arrowHead == Sysml2ArrowHead.ContainmentCrosshair) return "kuml-sysml2-edge-containment"
        // Wave C1: actor generalisation — hollow triangle at the general end.
        if (metadata.arrowHead == Sysml2ArrowHead.OpenTriangle) return "kuml-sysml2-edge-generalization"
        if (metadata.arrowHead == Sysml2ArrowHead.None) {
            return if (metadata.dashArray != null) "kuml-sysml2-edge-dashed-noarrow" else "kuml-sysml2-edge-binding"
        }
        // The SVG draws OpenAngle as an open V, so LaTeX must not turn it into a filled head (a different meaning).
        if (metadata.arrowHead == Sysml2ArrowHead.OpenAngle) {
            // Composed from the existing no-arrow styles so the shared preamble stays unchanged.
            return (if (metadata.dashArray != null) "kuml-sysml2-edge-dashed-noarrow" else "kuml-sysml2-edge-binding") +
                ", -{Straight Barb[length=2.4mm, width=2.4mm]}"
        }
        return if (metadata.dashArray != null) "kuml-sysml2-edge-dashed" else "kuml-sysml2-edge-solid"
    }

    private const val END_LABEL_OFFSET_PX: Float = 18f

    /**
     * Compose `node[midway, above=1pt, $EDGE_LABEL_BACKDROP]{…}` clauses for stereotype + label.
     *
     * The clauses sit **between the penultimate point and the final target**
     * in the TikZ draw command. When both stereotype and label are present
     * the stereotype sits slightly higher via `yshift=11pt`.
     *
     * Returns an empty string when both slots are null.
     */
    private const val EDGE_LABEL_BACKDROP = "fill=white, inner sep=1.5pt"

    private fun labelNodes(metadata: Sysml2EdgeMetadata): String {
        val stereotype = metadata.stereotype
        val label = metadata.label
        if (stereotype == null && label == null) return ""
        return buildString {
            if (stereotype != null && label != null) {
                append("node[midway, above=1pt, yshift=11pt, $EDGE_LABEL_BACKDROP]{\\textit{${escapeLatex(stereotype)}}} ")
                append("node[midway, above=1pt, $EDGE_LABEL_BACKDROP]{${escapeLatex(label)}} ")
            } else if (stereotype != null) {
                append("node[midway, above=1pt, $EDGE_LABEL_BACKDROP]{\\textit{${escapeLatex(stereotype)}}} ")
            } else if (label != null) {
                append("node[midway, above=1pt, $EDGE_LABEL_BACKDROP]{${escapeLatex(label)}} ")
            }
        }
    }

    /**
     * Escape a label string for safe inclusion in a TikZ node body.
     *
     * The SysML 2 label vocabulary includes the guillemets `«` and `»`,
     * the slash `/` (effect separator), square brackets (guards), and
     * arbitrary identifier characters. The dangerous ones for LaTeX are
     * `\`, `{`, `}`, `$`, `&`, `%`, `#`, `_`, `^`, `~`. Most SysML 2
     * labels won't trigger any of these; we still escape defensively.
     */
    private fun escapeLatex(s: String): String =
        buildString(s.length) {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\textbackslash{}")
                    '{' -> append("\\{")
                    '}' -> append("\\}")
                    '$' -> append("\\$")
                    '&' -> append("\\&")
                    '%' -> append("\\%")
                    '#' -> append("\\#")
                    '_' -> append("\\_")
                    '^' -> append("\\textasciicircum{}")
                    '~' -> append("\\textasciitilde{}")
                    '«' -> append("\\guillemotleft{}")
                    '»' -> append("\\guillemotright{}")
                    else -> append(ch)
                }
            }
        }

    private fun pt(p: Point): String = "(${fmtCoord(p.x)}pt, ${fmtCoord(-p.y)}pt)"
}
