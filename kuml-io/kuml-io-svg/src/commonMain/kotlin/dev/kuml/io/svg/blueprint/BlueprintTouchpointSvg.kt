package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Channel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.Touchpoint
import dev.kuml.blueprint.model.TouchpointSymbol
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a touchpoint symbol (circle/diamond/square/hexagon) with the channel
 * icon centred inside it.
 *
 * V3.1.23. Fix: the symbol only ever showed the channel glyph, never the
 * touchpoint's own name — two touchpoints on the same channel (e.g. two
 * different push notifications) were visually indistinguishable. When
 * [badge] is set (assigned by
 * [dev.kuml.blueprint.model.BlueprintGridConstants.legendEntries], one
 * number per touchpoint actually used in the diagram) a small numbered
 * circle is drawn at the symbol's upper-right corner; the legend band below
 * the grid maps each number back to its touchpoint's name.
 */
internal fun SvgBuilder.renderTouchpoint(
    tp: Touchpoint,
    channel: Channel?,
    cx: Double,
    cy: Double,
    size: Double = 26.0,
    badge: Int? = null,
) {
    val r = size / 2.0
    val shape =
        when (tp.symbol) {
            TouchpointSymbol.CIRCLE ->
                """<circle cx="${f(cx)}" cy="${f(cy)}" r="${f(r)}" fill="white" stroke="#333" stroke-width="1.5"/>"""
            TouchpointSymbol.DIAMOND ->
                """<polygon points="${f(cx)},${f(cy - r)} ${f(cx + r)},${f(cy)} ${f(cx)},${f(cy + r)} ${f(cx - r)},${f(cy)}" """ +
                    """fill="white" stroke="#333" stroke-width="1.5"/>"""
            TouchpointSymbol.SQUARE ->
                """<rect x="${f(
                    cx - r,
                )}" y="${f(cy - r)}" width="${f(size)}" height="${f(size)}" fill="white" stroke="#333" stroke-width="1.5"/>"""
            TouchpointSymbol.HEXAGON -> {
                val pts =
                    (0 until 6).joinToString(" ") { i ->
                        val a = (((60.0 * i) - 30.0) * PI / 180.0)
                        "${f(cx + r * cos(a))},${f(cy + r * sin(a))}"
                    }
                """<polygon points="$pts" fill="white" stroke="#333" stroke-width="1.5"/>"""
            }
        }
    rawXml(shape)
    // Channel icon, scaled from the 24x24 box to ~60% of the symbol.
    val iconScale = (size * 0.6) / 24.0
    val tx = cx - 12.0 * iconScale
    val ty = cy - 12.0 * iconScale
    val icon = BlueprintChannelIcons.fragmentFor(channel?.kind ?: ChannelKind.OTHER)
    rawXml("""<g transform="translate(${f(tx)},${f(ty)}) scale(${f(iconScale)})" color="#333">$icon</g>""")

    if (badge != null) {
        val badgeR = 7.0
        val badgeCx = cx + r * 0.8
        val badgeCy = cy - r * 0.8
        rawXml(
            """<circle cx="${f(badgeCx)}" cy="${f(badgeCy)}" r="${f(badgeR)}" fill="#333" stroke="white" stroke-width="1"/>""" +
                """<text x="${f(badgeCx)}" y="${f(badgeCy + 3.0)}" text-anchor="middle" """ +
                """font-size="9" font-weight="700" fill="white">$badge</text>""",
        )
    }
}

internal fun f(v: Double): String = fmt2(v.toFloat())
