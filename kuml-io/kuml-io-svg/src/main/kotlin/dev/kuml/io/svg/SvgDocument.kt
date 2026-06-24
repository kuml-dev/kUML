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

        // V3.0.11 — Canvas-Background. Without an explicit rect the SVG is
        // transparent and the host fill (e.g. Obsidian's dark editor pane)
        // shows through between nodes, making diagrams look broken.
        if (options.paintCanvasBackground) {
            val bg = theme.colors.background.toHex()
            val rect = """<rect x="0" y="0" width="${fmt(canvasW)}" height="${fmt(canvasH)}" fill="$bg"/>"""
            root.append(if (p) "  $rect\n" else rect)
        }

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

    internal fun buildDefs(
        b: SvgBuilder,
        theme: KumlTheme,
    ) {
        val c = theme.colors
        val ty = theme.typography
        val bo = theme.borders
        // Node fill defaults to the canvas background unless the theme
        // explicitly defines `nodeFill` (e.g. white canvas + tinted nodes).
        val nodeFill = c.effectiveNodeFill.toHex()
        val css =
            buildString {
                append(".kuml-class { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-interface { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-enum { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-component { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-state { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-usecase { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-system { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thickPx}; }\n")
                append(".kuml-container { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thinPx}; }\n")
                append(".kuml-c4component { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thinPx}; }\n")
                // UmlInstanceSpecification (Object Diagram). Ohne diese Regel fielen
                // Instanz-Knoten auf den SVG-Default `fill: black` zurück und überdeckten
                // Header und Slot-Compartment vollständig — derselbe Bug-Typ wie der
                // V3.0.11-Fix für `kuml-action`/`kuml-decision`. Konvention identisch zu
                // `.kuml-class`: Node-Fill + regulärer Border.
                append(".kuml-instance { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-title { font-family: ${ty.title.family}; font-size: ${ty.title.sizePt}px;")
                append(" font-weight: ${ty.title.weight}; fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-title-abstract { font-style: italic; }\n")
                append(".kuml-subtitle { font-family: ${ty.subtitle.family}; font-size: ${ty.subtitle.sizePt}px;")
                append(" font-weight: ${ty.subtitle.weight}; fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-body { font-family: ${ty.body.family}; font-size: ${ty.body.sizePt}px;")
                append(" fill: ${c.foreground.toHex()}; }\n")
                append(".kuml-small { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.muted.toHex()}; }\n")
                // V2.0.46 — Edge label: two-pass rendering. `kuml-edge-label-halo` is
                // a thicker stroked version drawn first (renderer emits two <text>
                // elements). Batik's `paint-order: stroke` is unreliable in 1.x — the
                // two-pass approach is the portable trick used since Graphviz.
                append(".kuml-edge-label { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.muted.toHex()}; }\n")
                append(".kuml-edge-label-halo { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.background.toHex()}; stroke: ${c.background.toHex()};")
                append(" stroke-width: 3px; stroke-linejoin: round; }\n")
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
                append(".kuml-frame { fill: none; stroke: ${c.border.toHex()}; stroke-width: ${bo.regularPx}; }\n")
                // V2.0.44 — UmlCollaboration is rendered as an outlined ellipse (UML 2.5 §11.7.1).
                // Without an explicit `fill: none` here Batik defaults to black-fill, swallowing
                // the text. The dashed-stroke pattern is supplied per-element via stroke-dasharray.
                append(".kuml-collaboration { fill: none; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                // V2.0.44 — UML 2.x port glyph: filled square on the component border.
                // Label uses small body font, foreground colour.
                append(".kuml-port { fill: ${c.foreground.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.thinPx}; }\n")
                append(".kuml-port-label { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.foreground.toHex()}; }\n")
                // V3.x — UML 2.x Composite-Structure internal connector (delegation or assembly).
                // Drawn as a plain line inside the parent component's local frame — no ELK routing.
                // Uses the same stroke style as a kuml-divider (thin, border colour) so it blends
                // with the composite-structure visual language. Label uses the small body font.
                append(".kuml-connector { fill: none; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-connector-label { font-family: ${ty.small.family}; font-size: ${ty.small.sizePt}px;")
                append(" fill: ${c.foreground.toHex()}; }\n")
                // V3.0.11 — UML-Activity-Diagramm (Action-Box, Decision-Raute, Fork/Join-Bar,
                // Initial-/Final-Marker). Diese Klassen werden in `UmlV11Svg.renderUmlActivityNode`
                // referenziert; ohne CSS-Regel renderten SVG-Konsumenten alle Shapes mit
                // `fill: black` (SVG-Default) — Action-Text und Decision-Label wurden vollständig
                // verschluckt, Edges sahen aus, als endeten sie im Nichts (Arrowhead vom
                // schwarzen Fill verdeckt). Konventionen:
                //  - kuml-action / kuml-decision: wie `.kuml-class` — Hintergrund-Fill + Border.
                //  - kuml-fork-bar / kuml-pseudostate: gefüllter Balken / gefüllter Initial-
                //    Marker (kanonische UML-Notation: Initial-Knoten ist ein vollgefüllter
                //    Punkt, Fork/Join eine massive Synchronisations-Bar).
                //  - kuml-final-outer: Donut-Außenring — Hintergrund-Fill, damit der
                //    gefüllte innere Pseudostate-Kreis als Ring erkennbar bleibt.
                append(".kuml-action { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-decision { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-fork-bar { fill: ${c.foreground.toHex()}; stroke: ${c.foreground.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-pseudostate { fill: ${c.foreground.toHex()}; stroke: ${c.foreground.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-final-outer { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                // V1.1 — UML-Deployment-Diagramm (Node-Würfel, Artifact, Stereotype-Box)
                // und UML-Timing-Diagramm (Lifeline-Frame, Step-Line). Diese Klassen werden
                // in `UmlV11Svg.kt` referenziert; ohne CSS-Regel rendern SVG-Konsumenten alle
                // Shapes mit `fill: black` (SVG-Default) — identischer Bug-Typ wie V3.0.11
                // (kuml-action/kuml-decision) und kuml-instance.
                // Konventionen:
                //  - kuml-node (Frontfläche) / kuml-artifact / kuml-stereotype-box:
                //    Hintergrund-Fill + regulärer Border — wie .kuml-class.
                //  - kuml-node-top / kuml-node-side (3D-Akzentflächen des Würfels):
                //    gedämpfte Fill (c.muted) für leichten Tiefeneffekt, gleicher Border.
                //  - kuml-artifact-ear (Eselsecke): gedämpfte Fill als Kontrast zur Hauptfläche.
                //  - kuml-timing-frame: Hintergrund-Fill + Border (Rahmen der Lifeline).
                //  - kuml-timing-line: Kanten-Stroke ohne Fill (Zustandswechsel-Pfad).
                append(".kuml-node { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-node-top { fill: ${c.muted.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-node-side { fill: ${c.muted.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-artifact { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-artifact-ear { fill: ${c.muted.toHex()}; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-stereotype-box { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-timing-frame { fill: $nodeFill; stroke: ${c.border.toHex()};")
                append(" stroke-width: ${bo.regularPx}; }\n")
                append(".kuml-timing-line { stroke: ${c.edge.toHex()}; stroke-width: ${bo.regularPx}; fill: none; }\n")
            }

        b.tag("style") {
            text(css)
        }

        // Arrow markers have been replaced by inline geometry rendered directly
        // adjacent to each edge via SvgInlineArrow.kt. The former <marker>/url(#id)
        // approach failed in Obsidian reading mode because DOMParser + appendChild
        // does not reliably register <defs> IDs in the host HTML document's ID table
        // (Electron adoption bug), causing arrowheads to silently disappear.
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }
}
