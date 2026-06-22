package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert ein [BpmnDataObject] als Dokumenten-Symbol mit geknickter oberer rechter Ecke.
 *
 * Bei Collection-DataObjects werden drei vertikale Striche am unteren Rand ergänzt.
 * Das optionale Label erscheint unterhalb des Symbols.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnDataObject(
    data: BpmnDataObject,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val foldSize = 10f

    builder.tag("g", mapOf("id" to xmlEscapeAttr(data.id))) {
        // Dokumenten-Pfad mit geknickter oberer rechter Ecke
        rawXml(
            """<path d="M${fmtF(x)},${fmtF(y)} L${fmtF(x + w - foldSize)},${fmtF(y)} """ +
                """L${fmtF(x + w)},${fmtF(y + foldSize)} L${fmtF(x + w)},${fmtF(y + h)} """ +
                """L${fmtF(x)},${fmtF(y + h)} Z" fill="white" stroke="#333" stroke-width="1.5"/>""",
        )
        // Fold-Dreieck: die überstehende Ecke
        rawXml(
            """<polyline points="${fmtF(x + w - foldSize)},${fmtF(y)} """ +
                """${fmtF(x + w - foldSize)},${fmtF(y + foldSize)} """ +
                """${fmtF(x + w)},${fmtF(y + foldSize)}" fill="none" stroke="#333" stroke-width="1"/>""",
        )

        if (data.collection) {
            // Collection: drei vertikale Striche am unteren Rand
            val cx = x + w / 2f
            val my = y + h - 8f
            listOf(-3f, 0f, 3f).forEach { dx ->
                rawXml(
                    """<line x1="${fmtF(cx + dx)}" y1="${fmtF(my)}" """ +
                        """x2="${fmtF(cx + dx)}" y2="${fmtF(my + 6f)}" """ +
                        """stroke="#333" stroke-width="1"/>""",
                )
            }
        }

        // Label unterhalb des DataObjects
        val label = data.name
        if (!label.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(x + w / 2f),
                    "y" to fmtF(y + h + 12f),
                    "text-anchor" to "middle",
                    "font-family" to "sans-serif",
                    "font-size" to "10",
                    "fill" to "#333",
                ),
            ) { text(label) }
        }
    }
}

private fun fmtF(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
