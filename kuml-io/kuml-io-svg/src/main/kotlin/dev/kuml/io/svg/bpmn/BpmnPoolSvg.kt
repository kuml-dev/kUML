package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert einen [BpmnParticipant] (Pool) als BPMN-Swimlane-Rahmen.
 *
 * Ein horizontaler Pool hat ein vertikales Titel-Band auf der linken Seite;
 * der Titel-Text wird um 90° gedreht (gegen den Uhrzeigersinn) dargestellt.
 * Ein vertikaler Pool hat ein horizontales Titel-Band oben.
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
internal fun renderBpmnParticipant(
    participant: BpmnParticipant,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag("g", mapOf("id" to xmlEscapeAttr(participant.id))) {
        renderPoolFrame(participant, x, y, w, h, this)
    }
}

/**
 * Rendert eine [BpmnLane] als Trennlinie mit kleinem Titel-Band.
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
internal fun renderBpmnLane(
    lane: BpmnLane,
    layout: NodeLayout,
    horizontal: Boolean,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag("g", mapOf("id" to xmlEscapeAttr(lane.id))) {
        renderLaneFrame(lane, x, y, w, h, horizontal, this)
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

internal const val POOL_TITLE_BAND_WIDTH = 30f
internal const val LANE_TITLE_BAND_WIDTH = 24f

internal fun renderPoolFrame(
    participant: BpmnParticipant,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    builder: SvgBuilder,
) {
    // Outer pool border
    builder.rawXml(
        """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
            """fill="white" stroke="#333" stroke-width="1.5" rx="3"/>""",
    )

    if (participant.horizontal) {
        // Vertical title band on the left side
        builder.rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(POOL_TITLE_BAND_WIDTH)}" """ +
                """height="${fmtF(h)}" fill="#f0f0f0" stroke="#333" stroke-width="1" rx="3"/>""",
        )
        val poolName = participant.name
        if (!poolName.isNullOrBlank()) {
            val tx = x + POOL_TITLE_BAND_WIDTH / 2f
            val ty = y + h / 2f
            builder.rawXml(
                """<text x="${fmtF(tx)}" y="${fmtF(ty)}" text-anchor="middle" dominant-baseline="middle" """ +
                    """font-family="sans-serif" font-size="12" font-weight="bold" fill="#333" """ +
                    """transform="rotate(-90,${fmtF(tx)},${fmtF(ty)})">${xmlEscapeContent(poolName)}</text>""",
            )
        }
    } else {
        // Horizontal title band on top
        builder.rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" """ +
                """height="${fmtF(POOL_TITLE_BAND_WIDTH)}" fill="#f0f0f0" stroke="#333" stroke-width="1"/>""",
        )
        val poolName = participant.name
        if (!poolName.isNullOrBlank()) {
            val tx = x + w / 2f
            val ty = y + POOL_TITLE_BAND_WIDTH / 2f + 4f
            builder.rawXml(
                """<text x="${fmtF(tx)}" y="${fmtF(ty)}" text-anchor="middle" """ +
                    """font-family="sans-serif" font-size="12" font-weight="bold" fill="#333">${xmlEscapeContent(poolName)}</text>""",
            )
        }
    }
}

internal fun renderLaneFrame(
    lane: BpmnLane,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    horizontal: Boolean,
    builder: SvgBuilder,
) {
    // Lane border (divider lines)
    builder.rawXml(
        """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
            """fill="none" stroke="#999" stroke-width="1"/>""",
    )

    if (horizontal) {
        // Small title band on the left within the lane
        builder.rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(LANE_TITLE_BAND_WIDTH)}" """ +
                """height="${fmtF(h)}" fill="#fafafa" stroke="#999" stroke-width="0.5"/>""",
        )
        val laneName = lane.name
        if (!laneName.isNullOrBlank()) {
            val tx = x + LANE_TITLE_BAND_WIDTH / 2f
            val ty = y + h / 2f
            builder.rawXml(
                """<text x="${fmtF(tx)}" y="${fmtF(ty)}" text-anchor="middle" dominant-baseline="middle" """ +
                    """font-family="sans-serif" font-size="11" fill="#555" """ +
                    """transform="rotate(-90,${fmtF(tx)},${fmtF(ty)})">${xmlEscapeContent(laneName)}</text>""",
            )
        }
    } else {
        // Small title band on top
        builder.rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" """ +
                """height="${fmtF(LANE_TITLE_BAND_WIDTH)}" fill="#fafafa" stroke="#999" stroke-width="0.5"/>""",
        )
        val laneName = lane.name
        if (!laneName.isNullOrBlank()) {
            val tx = x + w / 2f
            val ty = y + LANE_TITLE_BAND_WIDTH / 2f + 4f
            builder.rawXml(
                """<text x="${fmtF(tx)}" y="${fmtF(ty)}" text-anchor="middle" """ +
                    """font-family="sans-serif" font-size="11" fill="#555">${xmlEscapeContent(laneName)}</text>""",
            )
        }
    }
}

private fun fmtF(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
