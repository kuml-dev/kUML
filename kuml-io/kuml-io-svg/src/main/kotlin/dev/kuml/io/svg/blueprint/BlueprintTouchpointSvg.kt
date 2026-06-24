package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Channel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.Touchpoint
import dev.kuml.blueprint.model.TouchpointSymbol
import dev.kuml.io.svg.SvgBuilder

/**
 * Draws a touchpoint symbol (circle/diamond/square/hexagon) with the channel
 * icon centred inside it.
 *
 * V3.1.23
 */
internal fun SvgBuilder.renderTouchpoint(
    tp: Touchpoint,
    channel: Channel?,
    cx: Double,
    cy: Double,
    size: Double = 26.0,
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
                        val a = Math.toRadians((60.0 * i) - 30.0)
                        "${f(cx + r * Math.cos(a))},${f(cy + r * Math.sin(a))}"
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
}

internal fun f(v: Double): String {
    val i = v.toInt()
    return if (v == i.toDouble()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
