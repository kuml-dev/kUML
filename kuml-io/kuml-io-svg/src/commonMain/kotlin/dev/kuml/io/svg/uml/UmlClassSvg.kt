package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlClass

/**
 * Rendert eine [UmlClass] als drei-Sektions-Rechteck:
 * - Header: `«appliedStereotypes»` (wenn vorhanden) + Klassenname (fett, kursiv wenn `isAbstract`
 *   gemäß UML 2.5 — keine erfundene `«abstract»`-Stereotyp-Zeile)
 * - Optional: Tagged-Value-Compartment (wenn `theme.stereotypes.showTaggedValues = true`)
 * - Mitte: Attribute
 * - Unten: Operationen
 */
internal fun renderUmlClass(
    element: UmlClass,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val bo = theme.borders

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 18f

        // Applied stereotypes header (V1.1)
        val stereoAdv = StereotypeHelper.renderHeader(element, theme, this, w / 2f, cy)
        if (stereoAdv > 0f) {
            cy += stereoAdv
        }

        // Type name (bold, centered). Abstract classes get the italics-via-style hint (UML 2.5),
        // independent of whether a real stereotype header was rendered above it.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        val nameAttrs =
            buildMap<String, String> {
                put("class", nameClass)
                put("x", fmt(w / 2f))
                put("y", fmt(cy))
                put("text-anchor", "middle")
                if (element.isAbstract) put("font-style", "italic")
            }
        tag("text", nameAttrs) { text(element.name) }
        cy += 6f

        // Tagged-value compartment (V1.1, opt-in)
        val tvAdv = StereotypeHelper.renderTaggedValues(element, theme, this, w, cy)
        cy += tvAdv

        if (element.attributes.isNotEmpty() || element.operations.isNotEmpty()) {
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
            cy += 14f
        }

        for (attr in element.attributes) {
            val stereoPrefix = StereotypeHelper.featureStereotypeTspan(attr, theme)
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
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
            cy += 14f
        }

        for (op in element.operations) {
            val stereoPrefix = StereotypeHelper.featureStereotypeTspan(op, theme)
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
            ) { rawXml(stereoPrefix + xmlEscapeContent(op.format(theme))) }
            cy += 13f
        }
    }
}

private fun fmt(v: Float): String = fmt2(v)
