package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert einen [C4DeploymentNode] als gerundetes Rechteck mit `[Deployment Node]`-Header.
 *
 * - Stereotype-Header `[Deployment Node:<technology>]` (Technologie optional)
 * - Titel: Name (fett)
 * - Bei `instances > 1` zusätzlich `×N` rechts oben, damit Multiplicity sichtbar ist
 * - Optional umgebrochene Beschreibung
 */
internal fun renderC4DeploymentNode(
    element: C4DeploymentNode,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val r = theme.borders.cornerRadiusPx

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to fmt(r),
                "ry" to fmt(r),
                "class" to "kuml-node",
            ),
        )
        val tech = element.technology?.let { ":$it" } ?: ""
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to "18",
                "text-anchor" to "middle",
            ),
        ) { text("[Deployment Node$tech]") }
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to "36",
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
        if (element.instances > 1) {
            tag(
                "text",
                mapOf(
                    "class" to "kuml-small",
                    "x" to fmt(w - 8f),
                    "y" to "18",
                    "text-anchor" to "end",
                ),
            ) { text("×${element.instances}") }
        }
        element.description?.let { desc ->
            renderWrappedDescription(this, desc, w)
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
