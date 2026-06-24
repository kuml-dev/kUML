package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.svg.SvgBuilder

/**
 * Draws the emotion curve as an SVG polyline over the phase columns.
 *
 * Y-inversion: positive sentiment is drawn HIGHER (smaller y). Phases without a
 * sentiment are skipped, leaving a gap in the polyline (no NEUTRAL phantom point).
 *
 * V3.1.23
 */
internal fun SvgBuilder.renderEmotionCurve(
    curve: List<Pair<Phase, Sentiment?>>,
    columnCenters: List<Double>,
    bandTop: Double,
    bandHeight: Double,
    accent: String = "#fab500",
) {
    val inset = 14.0
    val top = bandTop + inset
    val usable = bandHeight - 2 * inset
    val points =
        curve.mapIndexedNotNull { i, (_, s) ->
            s?.let {
                val frac = (it.value + 2) / 4.0 // 0..1, VERY_NEGATIVE..VERY_POSITIVE
                val y = top + usable * (1.0 - frac) // invert: positive = higher
                columnCenters[i] to y
            }
        }
    if (points.size >= 2) {
        val d = points.joinToString(" ") { (x, y) -> "${f(x)},${f(y)}" }
        rawXml("""<polyline points="$d" fill="none" stroke="$accent" stroke-width="2.5"/>""")
    }
    points.forEach { (x, y) ->
        rawXml("""<circle cx="${f(x)}" cy="${f(y)}" r="4" fill="$accent"/>""")
    }
}
