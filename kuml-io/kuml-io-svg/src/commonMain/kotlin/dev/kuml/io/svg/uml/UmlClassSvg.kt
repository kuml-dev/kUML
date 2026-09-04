package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.Stereotypable
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty

/**
 * Rendert eine klassen-förmige Box (drei-Sektions-Rechteck) für [UmlClass]
 * und [UmlAssociationClass] — beide teilen sich exakt dieselbe visuelle
 * Darstellung:
 * - Header: `«appliedStereotypes»` (wenn vorhanden) + Name (fett, kursiv wenn
 *   `isAbstract` gemäß UML 2.5 — keine erfundene `«abstract»`-Stereotyp-Zeile)
 * - Optional: Tagged-Value-Compartment (wenn `theme.stereotypes.showTaggedValues = true`)
 * - Mitte: Attribute
 * - Unten: Operationen
 *
 * [renderUmlClass] (beide Overloads) delegiert hierher, statt die Zeichen-
 * logik zu duplizieren — die beiden Elementtypen unterscheiden sich nur in
 * ihrer zusätzlichen Relationship-Natur (siehe [UmlAssociationClass]-KDoc),
 * nicht in ihrem Box-Erscheinungsbild.
 */
private fun renderClassBox(
    id: String,
    name: String,
    isAbstract: Boolean,
    stereotypable: Stereotypable,
    attributes: List<UmlProperty>,
    operations: List<UmlOperation>,
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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "rect", attrs = mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 18f

        // Applied stereotypes header (V1.1)
        val stereoAdv = StereotypeHelper.renderHeader(element = stereotypable, theme = theme, builder = this, cx = w / 2f, cy = cy)
        if (stereoAdv > 0f) {
            cy += stereoAdv
        }

        // Type name (bold, centered). Abstract classes get the italics-via-style hint (UML 2.5),
        // independent of whether a real stereotype header was rendered above it.
        val nameClass = if (isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        val nameAttrs =
            buildMap<String, String> {
                put("class", nameClass)
                put("x", fmt(w / 2f))
                put("y", fmt(cy))
                put("text-anchor", "middle")
                if (isAbstract) put("font-style", "italic")
            }
        tag(name = "text", attrs = nameAttrs) { text(name) }
        cy += 6f

        // Tagged-value compartment (V1.1, opt-in)
        val tvAdv = StereotypeHelper.renderTaggedValues(element = stereotypable, theme = theme, builder = this, w = w, cy = cy)
        cy += tvAdv

        if (attributes.isNotEmpty() || operations.isNotEmpty()) {
            tag(
                name = "line",
                attrs =
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

        for (attr in attributes) {
            val stereoPrefix = StereotypeHelper.featureStereotypeTspan(element = attr, theme = theme)
            tag(
                name = "text",
                attrs = mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
            ) { rawXml(stereoPrefix + xmlEscapeContent(attr.format())) }
            cy += 13f
        }

        if (attributes.isNotEmpty() && operations.isNotEmpty()) {
            tag(
                name = "line",
                attrs =
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

        for (op in operations) {
            val stereoPrefix = StereotypeHelper.featureStereotypeTspan(element = op, theme = theme)
            tag(
                name = "text",
                attrs = mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
            ) { rawXml(stereoPrefix + xmlEscapeContent(op.format(theme))) }
            cy += 13f
        }
    }
}

internal fun renderUmlClass(
    element: UmlClass,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) = renderClassBox(
    id = element.id,
    name = element.name,
    isAbstract = element.isAbstract,
    stereotypable = element,
    attributes = element.attributes,
    operations = element.operations,
    layout = layout,
    theme = theme,
    builder = builder,
)

/**
 * Renders a [UmlAssociationClass] as the exact same box shape as [UmlClass]
 * (see [renderClassBox] KDoc). The association line and the dashed tether to
 * this box are drawn separately — see `renderUmlAssociation` (edge overload)
 * and `renderUmlAssociationClassTether` in `UmlEdgesSvg.kt`.
 */
internal fun renderUmlClass(
    element: UmlAssociationClass,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) = renderClassBox(
    id = element.id,
    name = element.name,
    isAbstract = element.isAbstract,
    stereotypable = element,
    attributes = element.attributes,
    operations = element.operations,
    layout = layout,
    theme = theme,
    builder = builder,
)

private fun fmt(v: Float): String = fmt2(v)
