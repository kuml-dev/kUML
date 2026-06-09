package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate

/**
 * Renders a [UmlPseudostate] as the appropriate UML shape.
 *
 * - INITIAL: filled circle (UML initial pseudostate)
 * - CHOICE: diamond (UML choice pseudostate)
 * - FORK / JOIN: thick horizontal bar
 * - Others: labeled rectangle fallback
 */
internal fun renderUmlPseudostate(
    element: UmlPseudostate,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = x + w / 2f
    val cy = y + h / 2f

    builder.tag("g", mapOf("id" to xmlEscapeAttr(element.id))) {
        when (element.kind) {
            PseudostateKind.INITIAL -> {
                val r = minOf(w, h) / 2f * 0.8f
                tag(
                    "circle",
                    mapOf(
                        "cx" to fmt(cx),
                        "cy" to fmt(cy),
                        "r" to fmt(r),
                        "class" to "kuml-class",
                        "fill" to "currentColor",
                    ),
                )
            }
            PseudostateKind.CHOICE -> {
                tag(
                    "polygon",
                    mapOf(
                        "points" to "${fmt(cx)},${fmt(y)} ${fmt(x + w)},${fmt(cy)} ${fmt(cx)},${fmt(y + h)} ${fmt(x)},${fmt(cy)}",
                        "class" to "kuml-class",
                    ),
                )
            }
            PseudostateKind.FORK, PseudostateKind.JOIN -> {
                tag(
                    "rect",
                    mapOf(
                        "x" to fmt(x),
                        "y" to fmt(cy - 4f),
                        "width" to fmt(w),
                        "height" to "8",
                        "class" to "kuml-class",
                        "fill" to "currentColor",
                    ),
                )
            }
            else -> {
                // Fallback: small labeled rectangle
                tag(
                    "rect",
                    mapOf(
                        "x" to fmt(x),
                        "y" to fmt(y),
                        "width" to fmt(w),
                        "height" to fmt(h),
                        "class" to "kuml-class",
                    ),
                )
                tag(
                    "text",
                    mapOf(
                        "class" to "kuml-small",
                        "x" to fmt(cx),
                        "y" to fmt(cy + 3f),
                        "text-anchor" to "middle",
                    ),
                ) { text(element.name) }
            }
        }
    }
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        "%.2f".format(java.util.Locale.ROOT, v)
    }
