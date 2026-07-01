package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent

/**
 * Draws the emotion curve as an SVG polyline over the phase columns, with the
 * read-aids that make it legible without prior knowledge (V3.1.28):
 *
 *  - three horizontal reference lines (+2 top, 0 = neutral dashed middle,
 *    −2 bottom) spanning the chart width,
 *  - a compact Y-axis scale (+2 / 0 / −2) at the left edge,
 *  - an "Emotion" band label in the label column,
 *  - sentiment-coloured points (red = negative … green = positive) with a
 *    `<title>` tooltip naming the phase and sentiment level.
 *
 * Y-inversion: positive sentiment is drawn HIGHER (smaller y). Phases without a
 * sentiment are skipped, leaving a gap in the polyline (no NEUTRAL phantom point).
 *
 * V3.1.23 — initial. V3.1.28 — axis, reference lines, label and colour coding.
 */
internal fun SvgBuilder.renderEmotionCurve(
    curve: List<Pair<Phase, Sentiment?>>,
    columnCenters: List<Double>,
    bandTop: Double,
    bandHeight: Double,
    contentLeft: Double,
    contentRight: Double,
    labelX: Double,
) {
    val inset = 14.0
    val top = bandTop + inset
    val usable = bandHeight - 2 * inset

    /** Maps a sentiment fraction (0..1, VERY_NEGATIVE..VERY_POSITIVE) to a y. */
    fun yForFrac(frac: Double): Double = top + usable * (1.0 - frac)

    fun yForSentiment(s: Sentiment): Double = yForFrac((s.value + 2) / 4.0)

    // ── Y-axis scale geometry: labels sit at the left edge, the reference lines
    //    must start to their RIGHT so they don't cross the "+2 / 0 / −2" text. ──
    val axisX = contentLeft + 3
    // Width reserved for the axis labels ("+2"/"−2" are the widest at font-size 9)
    // plus a small gap before the lines begin.
    val axisLabelGutter = 18.0
    val lineLeft = axisX + axisLabelGutter

    // ── 1. reference lines: +2 (top), 0 (neutral, dashed), −2 (bottom) ──
    val yTop = yForFrac(1.0)
    val yMid = yForFrac(0.5)
    val yBottom = yForFrac(0.0)
    rawXml(
        """<line x1="${f(lineLeft)}" y1="${f(yTop)}" x2="${f(contentRight)}" y2="${f(yTop)}" """ +
            """stroke="#e6e9ee" stroke-width="1"/>""",
    )
    rawXml(
        """<line x1="${f(lineLeft)}" y1="${f(yBottom)}" x2="${f(contentRight)}" y2="${f(yBottom)}" """ +
            """stroke="#e6e9ee" stroke-width="1"/>""",
    )
    rawXml(
        """<line x1="${f(lineLeft)}" y1="${f(yMid)}" x2="${f(contentRight)}" y2="${f(yMid)}" """ +
            """stroke="#c4cad4" stroke-width="1" stroke-dasharray="4 3"/>""",
    )

    // ── 2. Y-axis scale (+2 / 0 / −2) just inside the chart's left edge ──
    listOf(yTop to "+2", yMid to "0", yBottom to "−2").forEach { (y, lbl) ->
        tag(
            "text",
            mapOf(
                "x" to f(axisX),
                "y" to f(y + 3),
                "class" to "kuml-body",
                "font-size" to "9",
                "fill" to "#8a94a6",
            ),
        ) { text(lbl) }
    }

    // ── 3. band label "Emotion" in the label column ──
    tag(
        "text",
        mapOf(
            "x" to f(labelX),
            "y" to f(bandTop + bandHeight / 2),
            "class" to "kuml-body",
            "font-size" to "12",
            "font-weight" to "600",
            "fill" to "#1d2968",
        ),
    ) { text("Emotion") }

    // ── 4. polyline (neutral grey connector) ──
    val points =
        curve.mapIndexedNotNull { i, (phase, s) ->
            s?.let { Triple(columnCenters[i], yForSentiment(it), phase to it) }
        }
    if (points.size >= 2) {
        val d = points.joinToString(" ") { (x, y, _) -> "${f(x)},${f(y)}" }
        rawXml("""<polyline points="$d" fill="none" stroke="#8a94a6" stroke-width="2"/>""")
    }

    // ── 5. sentiment-coloured points with tooltip ──
    points.forEach { (x, y, meta) ->
        val (phase, sentiment) = meta
        val tip = (phase.name ?: phase.id) + ": " + sentimentLabel(sentiment)
        rawXml(
            """<circle class="bp-emotion-dot" cx="${f(x)}" cy="${f(y)}" r="5" """ +
                """fill="${sentimentColor(sentiment)}" stroke="#fff" stroke-width="1.5">""" +
                """<title>${xmlEscapeContent(tip)}</title></circle>""",
        )
    }
}

/** Semantic colour ramp: negative = red … neutral = grey … positive = green. */
private fun sentimentColor(s: Sentiment): String =
    when (s) {
        Sentiment.VERY_NEGATIVE -> "#c0143c"
        Sentiment.NEGATIVE -> "#e8743b"
        Sentiment.NEUTRAL -> "#9aa3b0"
        Sentiment.POSITIVE -> "#6cae3e"
        Sentiment.VERY_POSITIVE -> "#2e9e5b"
    }

private fun sentimentLabel(s: Sentiment): String =
    when (s) {
        Sentiment.VERY_NEGATIVE -> "sehr negativ"
        Sentiment.NEGATIVE -> "negativ"
        Sentiment.NEUTRAL -> "neutral"
        Sentiment.POSITIVE -> "positiv"
        Sentiment.VERY_POSITIVE -> "sehr positiv"
    }
