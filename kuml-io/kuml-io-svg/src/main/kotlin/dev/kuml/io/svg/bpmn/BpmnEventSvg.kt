package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert ein [BpmnEvent] als BPMN-Standard-Kreis-Symbol.
 *
 * - START: dünner Ring (bei nicht-unterbrechendem Boundary-Event gestrichelt)
 * - INTERMEDIATE: doppelter Ring
 * - END: dicker Ring
 *
 * Über den Ring wird das [BpmnEventSymbols]-Fragment für die EventDefinition gelegt.
 * Das optionale Label erscheint unterhalb des Rings.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnEvent(
    event: BpmnEvent,
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
    val r = minOf(w, h) / 2f - 2f

    val nonInterrupting =
        !event.interrupting &&
            (event.attachedToRef != null || event.position == EventPosition.START)
    val dashAttr = if (nonInterrupting) """ stroke-dasharray="4,2"""" else ""

    builder.tag("g", mapOf("id" to xmlEscapeAttr(event.id))) {
        when (event.position) {
            EventPosition.START -> {
                // "-circle" id lets SMIL fill animations target the ring directly;
                // animating fill on the parent <g> does not override the circle's own fill.
                rawXml(
                    """<circle id="${xmlEscapeAttr(event.id)}-circle" cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="white" stroke="#333" stroke-width="1.5"$dashAttr/>""",
                )
            }

            EventPosition.INTERMEDIATE -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="white" stroke="#333" stroke-width="1.5"$dashAttr/>""",
                )
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r - 3f)}" """ +
                        """fill="none" stroke="#333" stroke-width="1"$dashAttr/>""",
                )
            }

            EventPosition.END -> {
                rawXml(
                    """<circle id="${xmlEscapeAttr(event.id)}-circle" cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="white" stroke="#333" stroke-width="3"/>""",
                )
            }
        }

        // Symbol-Overlay: Symbol im 24×24-Normkoordinaten skalieren + zentrieren
        val throwing = event.behaviour == EventBehaviour.THROWING
        val symbol = BpmnEventSymbols.forDefinition(event.definition, throwing)
        if (symbol != null) {
            // Symbol soll innerhalb des Kreises bleiben: Faktor < 1.0, damit auch
            // die Ecken der 24×24-Normbox (Diagonale) den Ring nicht überragen.
            val scale = (r * 0.85f) / 12f
            val tx = cx - 12f * scale
            val ty = cy - 12f * scale
            rawXml(
                """<g transform="translate(${fmtF(tx)},${fmtF(ty)}) scale(${fmtF(scale)})" color="#333">""",
            )
            rawXml(symbol)
            rawXml("</g>")
        }

        // Label unterhalb des Events
        val label = event.name
        if (!label.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(y + h + 12f),
                    "text-anchor" to "middle",
                    "font-family" to "sans-serif",
                    "font-size" to "11",
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
