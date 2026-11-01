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
    extensionPoints: List<String> = emptyList(),
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
        val lineH0 = Sysml2ReqCard.UC_DESCRIPTION_LINE_H
        val extH =
            if (extensionPoints.isEmpty()) {
                0f
            } else {
                dev.kuml.layout.bridge.Sysml2LayoutBridge.UC_EXTENSION_BLOCK_GAP +
                    (1 + extensionPoints.size) * dev.kuml.layout.bridge.Sysml2LayoutBridge.UC_EXTENSION_LINE_H
            }
        val textBlockH = 16f + (description?.lines?.size ?: 0) * lineH0
        // Wave 2E-b: title (and description) move up, below them a divider, the heading and one line per extension point.
        val blockTop = ry - (textBlockH + extH) / 2f
        if (extensionPoints.isNotEmpty()) {
            val lineH = dev.kuml.layout.bridge.Sysml2LayoutBridge.UC_EXTENSION_LINE_H
            val dividerY = blockTop + textBlockH + 2f
            val half = rx * kotlin.math.sqrt(maxOf(0f, 1f - ((dividerY - ry) / ry) * ((dividerY - ry) / ry)))
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to fmt(rx - half),
                        "y1" to fmt(dividerY),
                        "x2" to fmt(rx + half),
                        "y2" to fmt(dividerY),
                        "class" to "kuml-divider",
                    ),
            )
            val lines = listOf("extension points") + extensionPoints
            lines.forEachIndexed { i, l ->
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body",
                            "x" to fmt(rx),
                            "y" to fmt(dividerY + 10f + i * lineH),
                            "text-anchor" to "middle",
                            "font-size" to "10",
                            "font-style" to if (i == 0) "italic" else "normal",
                        ),
                ) { text(l) }
            }
        }
        if (description == null || description.lines.isEmpty()) {
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(rx),
                        "y" to if (extensionPoints.isEmpty()) fmt(ry + 4f) else fmt(blockTop + 12f),
                        "text-anchor" to "middle",
                    ),
            ) { text(element.name) }
        } else {
            // Name on top, description (italic 10 px, max. 4 lines) below, the whole block
            // vertically centred inside the ellipse.
            val lineH = Sysml2ReqCard.UC_DESCRIPTION_LINE_H
            val top = blockTop
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
