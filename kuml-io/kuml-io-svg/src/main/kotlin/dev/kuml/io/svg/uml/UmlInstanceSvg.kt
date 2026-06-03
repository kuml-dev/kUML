package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInstanceValue

/**
 * Renders an [UmlInstanceSpecification] as a two-section rectangle:
 *  - Header: `name : ClassifierName`, underlined per UML 2.x convention.
 *  - Slot compartment: one `feature = value` line per slot.
 *
 * Anonymous instances (empty [UmlInstanceSpecification.name]) render with a
 * leading colon: `: ClassifierName`.
 */
internal fun renderUmlInstance(
    element: UmlInstanceSpecification,
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
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-instance"))

        // Underlined header: `name : Classifier` (or `: Classifier` if anonymous).
        val header =
            if (element.name.isEmpty()) {
                ": ${element.classifierName}"
            } else {
                "${element.name} : ${element.classifierName}"
            }

        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to "18",
                "text-anchor" to "middle",
                "text-decoration" to "underline",
            ),
        ) { text(xmlEscapeText(header)) }

        if (element.slots.isNotEmpty()) {
            var cy = 24f
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
            for (slot in element.slots) {
                val rhs =
                    when (val v = slot.value) {
                        is UmlInstanceValue.Literal -> v.text
                        is UmlInstanceValue.InstanceRef -> "→ ${v.instanceId}"
                        is UmlInstanceValue.Null -> "null"
                    }
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
                ) {
                    text(xmlEscapeText("${slot.featureName} = $rhs"))
                }
                cy += 13f
            }
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
