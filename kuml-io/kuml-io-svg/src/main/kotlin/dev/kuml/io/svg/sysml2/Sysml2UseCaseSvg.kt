package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.UseCaseDefinition

/**
 * Rendert eine SysML-2-[UseCaseDefinition] als Ellipse mit zentriertem Namen
 * (V2.0.7).
 *
 * Visuell identisch zur UML-UseCase-Form: die Ellipse füllt die Layout-Bounds,
 * der Name steht horizontal + vertikal zentriert. Keine Compartments,
 * keine Stereotyp-Zeile — UseCases sind in UC-Diagrammen leaf-Nodes ohne
 * Innenleben.
 *
 * Layout-Annahmen: die Bridge gibt `UC_USECASE_WIDTH × UC_USECASE_HEIGHT`
 * (= 160 × 70) als intrinsische Größe an. Die Ellipse skaliert auf jede
 * andere Bounds-Größe linear mit.
 *
 * Theme-Anbindung: nutzt die `kuml-usecase`-CSS-Klasse (gleich wie [UmlUseCase]).
 */
internal fun renderSysml2UseCase(
    element: UseCaseDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val rx = w / 2f
    val ry = h / 2f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "ellipse",
            mapOf(
                "cx" to fmt(rx),
                "cy" to fmt(ry),
                "rx" to fmt(rx),
                "ry" to fmt(ry),
                "class" to "kuml-usecase",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(rx),
                "y" to fmt(ry + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
