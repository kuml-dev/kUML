package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.Sysml2ReqCard
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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "ellipse",
            attrs =
                mapOf(
                    "cx" to fmt(rx),
                    "cy" to fmt(ry),
                    "rx" to fmt(rx),
                    "ry" to fmt(ry),
                    "class" to "kuml-usecase",
                ),
        )
        val description = if (element.description.isBlank()) null else Sysml2ReqCard.ucDescription(uc = element, ellipseWidth = w)
        if (description == null || description.lines.isEmpty()) {
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(rx),
                        "y" to fmt(ry + 4f),
                        "text-anchor" to "middle",
                    ),
            ) { text(element.name) }
        } else {
            // Name on top, description (italic 10 px, max. 4 lines) below, the whole block
            // vertically centred inside the ellipse.
            val lineH = Sysml2ReqCard.UC_DESCRIPTION_LINE_H
            val blockH = 16f + description.lines.size * lineH
            val top = ry - blockH / 2f
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(rx),
                        "y" to fmt(top + 12f),
                        "text-anchor" to "middle",
                    ),
            ) { text(element.name) }
            description.lines.forEachIndexed { i, line ->
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body kuml-uc-description",
                            "x" to fmt(rx),
                            "y" to fmt(top + 16f + (i + 1) * lineH - 2f),
                            "text-anchor" to "middle",
                            "font-size" to "10",
                            "font-style" to "italic",
                        ),
                ) { text(line) }
            }
            if (description.truncated) {
                tag(name = "title") { text(Sysml2ReqCard.clip(s = element.description, max = Sysml2ReqCard.UC_DESCRIPTION_MAX_CHARS)) }
            }
        }
    }
}

private fun fmt(v: Float): String = fmt3(v)
