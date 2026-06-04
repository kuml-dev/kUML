package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
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
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
