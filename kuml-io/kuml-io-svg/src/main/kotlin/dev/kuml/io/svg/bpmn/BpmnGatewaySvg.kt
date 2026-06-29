package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import kotlin.math.cos
import kotlin.math.sin

private const val BPMN_GATEWAY_SHAPE_H = 50f

/**
 * Rendert einen [BpmnGateway] als BPMN-Standard-Raute mit Typ-Symbol.
 *
 * - EXCLUSIVE: X
 * - INCLUSIVE: O (leerer Kreis)
 * - PARALLEL: + (Kreuz)
 * - EVENT_BASED: Doppelkreis + Pentagon
 * - COMPLEX: Asterisk (*)
 *
 * Das optionale Label erscheint unterhalb der Raute.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnGateway(
    gw: BpmnGateway,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = x + w / 2f
    val cy = y + BPMN_GATEWAY_SHAPE_H / 2f
    val hw = w / 2f
    val hh = BPMN_GATEWAY_SHAPE_H / 2f

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    builder.tag("g", mapOf("id" to xmlEscapeAttr(gw.id))) {
        // Raute — id on the polygon so SMIL fill animations can target it directly
        rawXml(
            """<polygon id="${xmlEscapeAttr(gw.id)}-diamond" """ +
                """points="${fmtF(cx)},${fmtF(cy - hh)} ${fmtF(cx + hw)},${fmtF(cy)} """ +
                """${fmtF(cx)},${fmtF(cy + hh)} ${fmtF(cx - hw)},${fmtF(cy)}" """ +
                """fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
        )

        // Typ-Symbol in der Mitte
        val symbolSize = minOf(hw, hh) * 0.6f

        when (gw.gatewayType) {
            GatewayType.EXCLUSIVE -> {
                val d = symbolSize * 0.6f
                rawXml(
                    """<line x1="${fmtF(cx - d)}" y1="${fmtF(cy - d)}" """ +
                        """x2="${fmtF(cx + d)}" y2="${fmtF(cy + d)}" stroke="$borderColor" stroke-width="2"/>""",
                )
                rawXml(
                    """<line x1="${fmtF(cx + d)}" y1="${fmtF(cy - d)}" """ +
                        """x2="${fmtF(cx - d)}" y2="${fmtF(cy + d)}" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.INCLUSIVE -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(symbolSize * 0.7f)}" """ +
                        """fill="none" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.PARALLEL -> {
                val arm = symbolSize * 0.8f
                rawXml(
                    """<line x1="${fmtF(cx)}" y1="${fmtF(cy - arm)}" """ +
                        """x2="${fmtF(cx)}" y2="${fmtF(cy + arm)}" stroke="$borderColor" stroke-width="2"/>""",
                )
                rawXml(
                    """<line x1="${fmtF(cx - arm)}" y1="${fmtF(cy)}" """ +
                        """x2="${fmtF(cx + arm)}" y2="${fmtF(cy)}" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.EVENT_BASED -> {
                val r1 = symbolSize * 0.8f
                val r2 = symbolSize * 0.6f
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r1)}" fill="none" stroke="$borderColor" stroke-width="1"/>""",
                )
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r2)}" fill="none" stroke="$borderColor" stroke-width="1"/>""",
                )
                val pr = symbolSize * 0.4f
                val pts =
                    (0 until 5).joinToString(" ") { i ->
                        val angle = Math.PI * (-0.5 + 2.0 * i / 5)
                        val px = cx + pr * cos(angle).toFloat()
                        val py = cy + pr * sin(angle).toFloat()
                        "${fmtF(px)},${fmtF(py)}"
                    }
                rawXml("""<polygon points="$pts" fill="none" stroke="$borderColor" stroke-width="1"/>""")
            }

            GatewayType.COMPLEX -> {
                val armR = symbolSize * 0.7f
                listOf(0.0, 60.0, 120.0).forEach { deg ->
                    val rad = Math.toRadians(deg)
                    val dx = (armR * cos(rad)).toFloat()
                    val dy = (armR * sin(rad)).toFloat()
                    rawXml(
                        """<line x1="${fmtF(cx - dx)}" y1="${fmtF(cy - dy)}" """ +
                            """x2="${fmtF(cx + dx)}" y2="${fmtF(cy + dy)}" stroke="$borderColor" stroke-width="2"/>""",
                    )
                }
            }
        }

        // Label
        val label = gw.name
        if (!label.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(y + BPMN_GATEWAY_SHAPE_H + 12f),
                    "text-anchor" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "11",
                    "fill" to textColor,
                ),
            ) { text(label) }
        }
    }
}

private fun fmtF(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
