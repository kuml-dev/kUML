package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlComponent

/**
 * Rendert eine [UmlComponent] — Rechteck mit zwei kleinen Rechteck-Glyphen
 * rechts oben (UML-Komponenten-Symbol) und dem Klassennamen.
 *
 * In V1.1: Wenn [UmlComponent.appliedStereotypes] gesetzt sind, werden diese als
 * zusätzliche `«…»`-Zeile vor dem `«component»`-Keyword gerendert.
 */
internal fun renderUmlComponent(
    element: UmlComponent,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = (w - 20f) / 2f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-component"))

        // Component glyph: two small rects protruding at top-right
        val gx = w - 16f
        tag(
            "rect",
            mapOf(
                "x" to fmt(gx - 4f),
                "y" to "6",
                "width" to "12",
                "height" to "6",
                "class" to "kuml-component",
            ),
        )
        tag(
            "rect",
            mapOf(
                "x" to fmt(gx - 4f),
                "y" to "16",
                "width" to "12",
                "height" to "6",
                "class" to "kuml-component",
            ),
        )

        var cy = 20f

        // Applied stereotypes header (V1.1) — prepended before «component»
        val stereoAdv = StereotypeHelper.renderHeader(element, theme, this, cx, cy)
        cy += stereoAdv

        // Fixed «component» keyword always present
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(cx),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text("«component»") }
        cy += 15f

        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }

        // V1.1.3 — feature compartments (attributes/operations) when present.
        // Backward-compat: feature-free components render exactly as before
        // (no extra dividers, no extra lines).
        val hasFeatures = element.attributes.isNotEmpty() || element.operations.isNotEmpty()
        if (hasFeatures) {
            cy += 6f

            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 12f

            for (attr in element.attributes) {
                val stereoPrefix = StereotypeHelper.featureStereotypeTspan(attr, theme)
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { rawXml(stereoPrefix + xmlEscapeContent(attr.format())) }
                cy += 13f
            }

            if (element.attributes.isNotEmpty() && element.operations.isNotEmpty()) {
                tag(
                    "line",
                    mapOf(
                        "x1" to "0",
                        "y1" to fmt(cy),
                        "x2" to fmt(w),
                        "y2" to fmt(cy),
                        "class" to "kuml-divider",
                    ),
                )
                cy += 12f
            }

            for (op in element.operations) {
                val stereoPrefix = StereotypeHelper.featureStereotypeTspan(op, theme)
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { rawXml(stereoPrefix + xmlEscapeContent(op.format(theme))) }
                cy += 13f
            }
        }

        // V2.0.44 — Ports on left/right borders. Each port is a 12×12 black-filled
        // square half-overlapping the border (UML 2.x convention) with its name as
        // a label INSIDE the box, next to the square. Ports are assigned to sides
        // alternately (even index → left, odd index → right) so up to two columns
        // of ports can co-exist without overlap. Vertical placement is even-spaced
        // per side: position i / (count + 1) of the box height. Labels INSIDE
        // because labels-outside would be clipped by the canvas — ELK does not
        // know about ports yet, so we cannot reserve outside whitespace. The
        // [UmlContentSizeProvider.componentSize] computation reserves enough
        // horizontal width (left + right port-label widths) so the inside labels
        // never overlap the centred title.
        renderPorts(element, w, h)
    }
}

private fun SvgBuilder.renderPorts(
    element: UmlComponent,
    w: Float,
    h: Float,
) {
    if (element.ports.isEmpty()) return

    val portSize = 12f
    val labelGap = 4f
    val leftPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 0 }
    val rightPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 1 }

    leftPorts.forEachIndexed { i, port ->
        val py = h * (i + 1) / (leftPorts.size + 1) - portSize / 2f
        tag(
            "rect",
            mapOf(
                "x" to fmt(-portSize / 2f),
                "y" to fmt(py),
                "width" to fmt(portSize),
                "height" to fmt(portSize),
                "class" to "kuml-port",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(portSize / 2f + labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "start",
            ),
        ) { text(port.name) }
    }

    rightPorts.forEachIndexed { i, port ->
        val py = h * (i + 1) / (rightPorts.size + 1) - portSize / 2f
        tag(
            "rect",
            mapOf(
                "x" to fmt(w - portSize / 2f),
                "y" to fmt(py),
                "width" to fmt(portSize),
                "height" to fmt(portSize),
                "class" to "kuml-port",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(w - portSize / 2f - labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "end",
            ),
        ) { text(port.name) }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
