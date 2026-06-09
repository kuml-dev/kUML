package dev.kuml.io.svg

import dev.kuml.layout.LayoutResult
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Baut das SVG-Wurzel-Dokument mit `<defs>`, `<style>`, `<g id="nodes">` und
 * `<g id="edges">` auf.
 *
 * Die eigentliche Node- und Edge-Bestückung erfolgt durch die Aufrufer-Callbacks.
 *
 * Beispiel:
 * ```kotlin
 * val svg = SvgDocument.render(layoutResult, theme, options) { nodeGroup, edgeGroup ->
 *     nodeGroup.tag("rect", ...)
 *     edgeGroup.tag("line", ...)
 * }
 * ```
 */
internal object SvgDocument {
    fun render(
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
        populate: (nodes: SvgBuilder, edges: SvgBuilder) -> Unit,
    ): String {
        val padding = options.paddingPx
        val canvasW = layoutResult.canvas.width + 2 * padding
        val canvasH = layoutResult.canvas.height + 2 * padding
        val p = options.prettyPrint

        val root = StringBuilder()

        if (options.includeXmlDeclaration) {
            root.append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (p) root.append("\n")
        }

        if (options.embedThemeAsComment) {
            val engineId = layoutResult.engineId.value
            root.append("<!-- kUML SVG · theme: ${theme.name} · engine: $engineId -->")
            if (p) root.append("\n")
        }

        val svgAttrs =
            linkedMapOf(
                "xmlns" to "http://www.w3.org/2000/svg",
                "viewBox" to "0 0 ${fmt(canvasW)} ${fmt(canvasH)}",
                "width" to fmt(canvasW),
                "height" to fmt(canvasH),
            )

        val defsBuilder = SvgBuilder(p, if (p) 2 else 0)
        buildDefs(defsBuilder, theme)

        val nodesBuilder = SvgBuilder(p, if (p) 4 else 0)
        val edgesBuilder = SvgBuilder(p, if (p) 4 else 0)
        populate(nodesBuilder, edgesBuilder)

        // Assemble <svg>
        val svgAttrsStr = svgAttrs.entries.joinToString(if (p) "\n     " else " ") { (k, v) -> """$k="$v"""" }
        root.append("<svg $svgAttrsStr>")
        if (p) root.append("\n")

        // <defs>
        root.append(if (p) "  <defs>\n" else "<defs>")
        root.append(defsBuilder.toString())
        root.append(if (p) "  </defs>\n" else "</defs>")

        // <g id="nodes">
        root.append(if (p) "  <g id=\"nodes\">\n" else "<g id=\"nodes\">")
        root.append(nodesBuilder.toString())
        root.append(if (p) "  </g>\n" else "</g>")

        // <g id="edges">
        root.append(if (p) "  <g id=\"edges\">\n" else "<g id=\"edges\">")
        root.append(edgesBuilder.toString())
        root.append(if (p) "  </g>\n" else "</g>")

        root.append("</svg>")
        if (p) root.append("\n")

        return root.toString()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildDefs(
        b: SvgBuilder,
        theme: KumlTheme,
    ) {
        val c = theme.colors
        val ty = theme.typography
        val bo = theme.borders
        val css =
            buildString {
                append(".kuml-class { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-interface { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-enum { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-component { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-state { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-usecase { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-system { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thickPx}; }\n")
                append(".kuml-container { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thinPx}; }\n")
                append(".kuml-c4component { fill: ${c.background.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thinPx}; }\n")
                append(".kuml-title { font-family: ${ty.title.family}; font-size: ${ty.title.sizePt}px;")
                append(" font-weight: ${ty.title.weight}; fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-title-abstract { font-style: italic; }\n")
                append(".kuml-subtitle { font-family: ${ty.subtitle.family}; font-size: ${ty.subtitle.sizePt}px;")
                append(" font-weight: ${ty.subtitle.weight}; fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-body { font-family: ${ty.body.family}; font-size: ${ty.body.sizePt}px;")
                append(" fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-small { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.muted.toHex()}; }\n")
                append(".kuml-stereotype { font-family: ${ty.stereotype.family};")
                append(" font-size: ${ty.stereotype.sizePt}px; font-style: italic;")
                append(" fill: ${c.muted.toHex()}; }\n")
                // V1.1: tagged-value compartment text — slightly smaller than body, italic
                val tvFontSize = theme.stereotypes.taggedValueFontSize
                append(".kuml-tagged-value { font-family: ${ty.body.family};")
                append(" font-size: ${tvFontSize}px; font-style: italic;")
                append(" fill: ${c.muted.toHex()}; }\n")
                append(".kuml-edge { stroke: ${c.edge.toHex()}; stroke-width: ${bo.regularPx}; fill: none; }\n")
                append(
                    ".kuml-edge-dashed { stroke: ${c.edgeMuted.toHex()}; stroke-width: ${bo.regularPx};" +
                        " fill: none; stroke-dasharray: 8 4; }\n",
                )
                append(".kuml-divider { stroke: ${c.border.toHex()}; stroke-width: ${bo.thinPx}; }\n")
                append(".kuml-actor { stroke: ${c.foreground.toHex()}; stroke-width: ${bo.regularPx}; fill: none; }\n")
            }

        b.tag("style") {
            text(css)
        }

        // Arrow markers
        buildMarkers(b, theme)
    }

    private fun buildMarkers(
        b: SvgBuilder,
        theme: KumlTheme,
    ) {
        val edgeColor = theme.colors.edge.toHex()
        val edgeMutedColor = theme.colors.edgeMuted.toHex()

        // Open arrowhead (association, dependency)
        b.tag(
            "marker",
            mapOf(
                "id" to "arrow-open",
                "markerWidth" to "10",
                "markerHeight" to "10",
                "refX" to "9",
                "refY" to "5",
                "orient" to "auto",
                "markerUnits" to "strokeWidth",
            ),
        ) {
            tag(
                "path",
                mapOf(
                    "d" to "M 1 1 L 9 5 L 1 9",
                    "stroke" to edgeColor,
                    "stroke-width" to "1.5",
                    "fill" to "none",
                ),
            )
        }

        // Open arrowhead muted (dashed edges)
        b.tag(
            "marker",
            mapOf(
                "id" to "arrow-open-muted",
                "markerWidth" to "10",
                "markerHeight" to "10",
                "refX" to "9",
                "refY" to "5",
                "orient" to "auto",
                "markerUnits" to "strokeWidth",
            ),
        ) {
            tag(
                "path",
                mapOf(
                    "d" to "M 1 1 L 9 5 L 1 9",
                    "stroke" to edgeMutedColor,
                    "stroke-width" to "1.5",
                    "fill" to "none",
                ),
            )
        }

        // Hollow triangle arrowhead (generalization, interface realization)
        b.tag(
            "marker",
            mapOf(
                "id" to "arrow-triangle",
                "markerWidth" to "12",
                "markerHeight" to "12",
                "refX" to "11",
                "refY" to "6",
                "orient" to "auto",
                "markerUnits" to "strokeWidth",
            ),
        ) {
            tag(
                "polygon",
                mapOf(
                    "points" to "1 1 11 6 1 11",
                    "stroke" to edgeColor,
                    "stroke-width" to "1.5",
                    "fill" to "white",
                ),
            )
        }

        // Hollow triangle arrowhead muted (interface realization dashed)
        b.tag(
            "marker",
            mapOf(
                "id" to "arrow-triangle-muted",
                "markerWidth" to "12",
                "markerHeight" to "12",
                "refX" to "11",
                "refY" to "6",
                "orient" to "auto",
                "markerUnits" to "strokeWidth",
            ),
        ) {
            tag(
                "polygon",
                mapOf(
                    "points" to "1 1 11 6 1 11",
                    "stroke" to edgeMutedColor,
                    "stroke-width" to "1.5",
                    "fill" to "white",
                ),
            )
        }
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }
}
