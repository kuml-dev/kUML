package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlState

/** Avg width of an 11pt `kuml-body` character (px) — mirrors BODY_CHAR_PX in UmlContentSizeProvider. */
private const val AVG_CHAR_WIDTH_PX = 6.6

/** Line height for a wrapped simple-state name (px) — mirrors WRAP_LINE_H used elsewhere. */
private const val LINE_HEIGHT_PX = 13.0f

/** Horizontal padding (left + right) reserved around a simple state's name. */
private const val H_PADDING_PX = 16.0

/**
 * Wraps [text] into lines that fit within [maxWidthPx], splitting only at
 * word boundaries. A single word wider than the column is kept on its own
 * line (never truncated) — mirrors wrapText/wrapWords in the blueprint and
 * SysML 2 requirement renderers.
 */
internal fun wrapStateName(
    text: String,
    maxWidthPx: Double,
): List<String> {
    val maxChars = (maxWidthPx / AVG_CHAR_WIDTH_PX).toInt().coerceAtLeast(1)
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (candidate.length <= maxChars) {
            current.clear()
            current.append(candidate)
        } else {
            if (current.isNotEmpty()) lines += current.toString()
            current.clear()
            current.append(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}

/**
 * Rendert einen [UmlState] als gerundetes Rechteck (rx=12, ry=12).
 *
 * Zwei Varianten:
 * - **Einfacher State** ([UmlState.substates] leer): Name zentriert in der Box. Fix:
 *   der Name wurde bisher immer einzeilig gezeichnet, egal wie lang — in der fixen
 *   160×80-Box (siehe [dev.kuml.layout.bridge.UmlContentSizeProvider], das vorher
 *   keinen [UmlState]-Zweig hatte) lief ein Name wie "Antragsvorschlag Eingereicht
 *   auf Agora-Platform" links und rechts über den Rand hinaus. Der Name wird jetzt
 *   wortweise umgebrochen und der Textblock vertikal zentriert; die Box wächst
 *   content-aware in der Höhe (siehe [dev.kuml.layout.bridge.UmlContentSizeProvider]),
 *   sodass der zentrierte Block immer hineinpasst.
 * - **Composite State** ([UmlState.substates] nicht leer): Name oben linksbündig bei y=18,
 *   darunter eine horizontale Trennlinie (`kuml-divider`) bei y=28, die die Namenszeile
 *   vom Substate-Bereich (der durch ELK-Layout befüllt wird) abgrenzt. Unverändert —
 *   die Box wird von ELK aus den Substates heraus dimensioniert, nicht aus dem Namen.
 */
internal fun renderUmlState(
    element: UmlState,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmt(w),
                    "height" to fmt(h),
                    "rx" to "12",
                    "ry" to "12",
                    "class" to "kuml-state",
                ),
        )
        if (element.substates.isNotEmpty()) {
            // Composite state: name at top, horizontal divider below
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(w / 2f),
                        "y" to "18",
                        "text-anchor" to "middle",
                    ),
            ) { text(element.name) }
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to "0",
                        "y1" to "28",
                        "x2" to fmt(w),
                        "y2" to "28",
                        "class" to "kuml-divider",
                    ),
            )
        } else {
            // Simple state: name word-wrapped and vertically centered. A
            // single-line name reduces to exactly the old firstLineY = h/2+4,
            // so short names render byte-identical to before.
            val lines = wrapStateName(text = element.name, maxWidthPx = w.toDouble() - H_PADDING_PX)
            val blockOffset = (lines.size - 1) * LINE_HEIGHT_PX / 2f
            val firstLineY = h / 2f - blockOffset + 4f
            if (lines.size <= 1) {
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body",
                            "x" to fmt(w / 2f),
                            "y" to fmt(firstLineY),
                            "text-anchor" to "middle",
                        ),
                ) { text(lines.firstOrNull() ?: element.name) }
            } else {
                rawXml(
                    buildString {
                        append("""<text x="${fmt(w / 2f)}" y="${fmt(firstLineY)}" """)
                        append("""text-anchor="middle" class="kuml-body">""")
                        lines.forEachIndexed { idx, line ->
                            val dy = if (idx == 0) "0" else fmt(LINE_HEIGHT_PX)
                            append("""<tspan x="${fmt(w / 2f)}" dy="$dy">${xmlEscapeContent(line)}</tspan>""")
                        }
                        append("</text>")
                    },
                )
            }
        }
    }
}

private fun fmt(v: Float): String = fmt2(v)
