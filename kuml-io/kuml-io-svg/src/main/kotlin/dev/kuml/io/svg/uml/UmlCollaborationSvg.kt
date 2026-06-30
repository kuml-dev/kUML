package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlCollaboration

/**
 * Renders a [UmlCollaboration] as a dashed ellipse (UML 2.5 §11.7.1 notation).
 *
 * Layout:
 * - Dashed ellipse as outer boundary (`stroke-dasharray="4 4"`).
 * - If the collaboration has applied stereotypes, the `«stereotype»` header
 *   is rendered above the name via [StereotypeHelper.renderHeader].
 * - Collaboration name centred in the ellipse.
 *
 * Roles are NOT rendered as embedded nodes here. Role connectors and their
 * participants belong to a composite-structure context (Ticket 4, Out of Scope
 * for Ticket 3.5).
 */
internal fun renderUmlCollaboration(
    element: UmlCollaboration,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // Dashed ellipse — standard UML 2.5 collaboration notation
        // V2.0.44: `fill="none"` set as an XML attribute (presentation-attribute
        // precedence beats user-agent default, which on Batik fills shapes
        // with black even when CSS sets `fill: none` on the class).
        tag(
            "ellipse",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "rx" to fmt(cx),
                "ry" to fmt(cy),
                "class" to "kuml-collaboration",
                "fill" to "none",
                "stroke-dasharray" to "4 4",
            ),
        )

        // Optional stereotype header «…» — uses StereotypeHelper from Ticket 3
        val stereotypeOffset =
            StereotypeHelper.renderHeader(
                element = element,
                theme = theme,
                builder = this,
                cx = cx,
                cy = cy - theme.stereotypes.headerFontSize / 2f - 2f,
            )

        // Collaboration name centred in the ellipse (shifted down by stereotype height)
        val nameY = cy + theme.stereotypes.headerFontSize / 2f + stereotypeOffset / 2f + 4f
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(if (stereotypeOffset > 0f) nameY else cy + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
