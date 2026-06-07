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
 *  - Labels: emitted as TikZ `node[midway, above]{…}` clauses on the line
 *    draw. Stereotype renders italic via the inline `\textit{…}` wrapping;
 *    plain labels stay upright. Two labels stack vertically using two
 *    `node[…, above]{…}` clauses with `yshift`.
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
        when (route) {
            is EdgeRoute.Direct -> renderDirect(route, metadata, style, options, out)
            is EdgeRoute.OrthogonalRounded ->
                renderPolyline(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                    metadata,
                    style,
                    options,
                    out,
                )
            is EdgeRoute.TreeRounded ->
                renderPolyline(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                    metadata,
                    style,
                    options,
                    out,
                )
            is EdgeRoute.Bezier -> renderBezier(route, metadata, style, options, out)
        }
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
        if (metadata.arrowHead == Sysml2ArrowHead.None) {
            return if (metadata.dashArray != null) "kuml-sysml2-edge-dashed-noarrow" else "kuml-sysml2-edge-binding"
        }
        return if (metadata.dashArray != null) "kuml-sysml2-edge-dashed" else "kuml-sysml2-edge-solid"
    }

    /**
     * Compose `node[midway, above]{…}` clauses for stereotype + label.
     *
     * The clauses sit **between the penultimate point and the final target**
     * in the TikZ draw command. When both stereotype and label are present
     * the stereotype sits slightly higher via `yshift=8pt`.
     *
     * Returns an empty string when both slots are null.
     */
    private fun labelNodes(metadata: Sysml2EdgeMetadata): String {
        val stereotype = metadata.stereotype
        val label = metadata.label
        if (stereotype == null && label == null) return ""
        return buildString {
            if (stereotype != null && label != null) {
                append("node[midway, above, yshift=8pt]{\\textit{${escapeLatex(stereotype)}}} ")
                append("node[midway, above]{${escapeLatex(label)}} ")
            } else if (stereotype != null) {
                append("node[midway, above]{\\textit{${escapeLatex(stereotype)}}} ")
            } else if (label != null) {
                append("node[midway, above]{${escapeLatex(label)}} ")
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
