package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnActivity
import dev.kuml.bpmn.model.MultiInstanceLoop
import dev.kuml.bpmn.model.StandardLoop
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert Loop/Multi-Instance-Marker am unteren Rand einer BPMN-Activity-Box.
 *
 * - [StandardLoop]: Pfeil-Kreis ↻
 * - [MultiInstanceLoop] sequential: Drei horizontale Striche ≡
 * - [MultiInstanceLoop] parallel: Drei vertikale Striche ‖
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnTaskMarkers(
    activity: BpmnActivity,
    layout: NodeLayout,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val markerY = y + h - 16f
    val markerCx = x + w / 2f

    val markerColor = theme.colors.border.toHex()

    when (val lc = activity.loopCharacteristics) {
        is StandardLoop -> {
            // Pfeil-Kreis ↻
            builder.rawXml(
                """<path d="M ${fmtF(markerCx - 6f)},${fmtF(markerY + 8f)} """ +
                    """A 6,6 0 1,1 ${fmtF(markerCx + 5f)},${fmtF(markerY + 3f)}" """ +
                    """fill="none" stroke="$markerColor" stroke-width="1.5"/>""",
            )
            builder.rawXml(
                """<polyline points="${fmtF(markerCx + 5f)},${fmtF(markerY)} """ +
                    """${fmtF(markerCx + 5f)},${fmtF(markerY + 3f)} """ +
                    """${fmtF(markerCx + 8f)},${fmtF(markerY + 3f)}" """ +
                    """fill="none" stroke="$markerColor" stroke-width="1.5"/>""",
            )
        }

        is MultiInstanceLoop -> {
            if (lc.sequential) {
                // Drei horizontale Striche ≡
                listOf(-4f, 0f, 4f).forEach { dy ->
                    builder.rawXml(
                        """<line x1="${fmtF(markerCx - 5f)}" y1="${fmtF(markerY + 8f + dy)}" """ +
                            """x2="${fmtF(markerCx + 5f)}" y2="${fmtF(markerY + 8f + dy)}" """ +
                            """stroke="$markerColor" stroke-width="1.5"/>""",
                    )
                }
            } else {
                // Drei vertikale Striche ‖
                listOf(-4f, 0f, 4f).forEach { dx ->
                    builder.rawXml(
                        """<line x1="${fmtF(markerCx + dx)}" y1="${fmtF(markerY + 2f)}" """ +
                            """x2="${fmtF(markerCx + dx)}" y2="${fmtF(markerY + 14f)}" """ +
                            """stroke="$markerColor" stroke-width="1.5"/>""",
                    )
                }
            }
        }

        null -> {
            // Kein Marker
        }
    }
}

private fun fmtF(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
