package dev.kuml.io.svg.c4

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.layout.TextWrap

/**
 * Konstanten, die zwischen C4-SVG-Renderer und `C4ContentSizeProvider` synchron
 * gehalten werden müssen.
 *
 * **Vertrag**: identische `(text, maxWidthPx, charPx)`-Tripel müssen identische
 * Wrap-Zeilen liefern, sonst dimensioniert die Bridge eine N-zeilige Box und
 * der Renderer zeichnet N±1 Zeilen hinein — genau der Stack-aus-der-Box-Bug,
 * den dieses Modul behebt.
 *
 * Die Werte spiegeln die Konstanten in [dev.kuml.layout.bridge.C4ContentSizeProvider].
 */
internal object C4DescriptionLayout {
    /** Avg width of a 9pt sans-serif char (description — `kuml-small`). */
    const val DESC_CHAR_PX: Float = 5.4f

    /** Horizontal padding (left + right) inside a C4 box. */
    const val H_PAD: Float = 12f

    /** y-coordinate of the first description line (matches the previous `y="52"`). */
    const val DESC_FIRST_BASELINE_Y: Float = 52f

    /** Line height for `kuml-small` (description) text. */
    const val DESC_LINE_H: Float = 13f
}

/**
 * Rendert die [desc]-Beschreibung als eine oder mehrere `<tspan>`-Zeilen mit
 * `kuml-small`-Style. Die Wrap-Logik nutzt [TextWrap.wrapToWidth] mit denselben
 * Konstanten wie der `C4ContentSizeProvider`, sodass die Box-Höhe immer zur
 * tatsächlichen Zeilenzahl passt.
 *
 * @param builder Aktiver SVG-Builder (innerhalb des `<g>`-Wrappers des Knotens).
 * @param desc Beschreibungstext (roh, ohne XML-Escaping).
 * @param boxWidth Gesamtbreite der C4-Box in px. Inner-Width für die Umbrüche
 *                 ist `boxWidth - 2 * H_PAD`.
 * @param firstBaselineY Optional — y der ersten Wrap-Zeile. Default 52 (Standard
 *                 für SoftwareSystem, Container, Component).
 */
internal fun renderWrappedDescription(
    builder: SvgBuilder,
    desc: String,
    boxWidth: Float,
    firstBaselineY: Float = C4DescriptionLayout.DESC_FIRST_BASELINE_Y,
    centerX: Float = boxWidth / 2f,
    maxInnerWidth: Float = boxWidth - 2f * C4DescriptionLayout.H_PAD,
) {
    val lines =
        TextWrap.wrapToWidth(
            text = desc,
            maxWidthPx = maxInnerWidth,
            charPx = C4DescriptionLayout.DESC_CHAR_PX,
        )
    if (lines.isEmpty()) return

    builder.tag(
        "text",
        mapOf(
            "class" to "kuml-small",
            "x" to fmtCoord(centerX),
            "y" to fmtCoord(firstBaselineY),
            "text-anchor" to "middle",
        ),
    ) {
        lines.forEachIndexed { idx, line ->
            tag(
                "tspan",
                mapOf(
                    "x" to fmtCoord(centerX),
                    "dy" to if (idx == 0) "0" else fmtCoord(C4DescriptionLayout.DESC_LINE_H),
                ),
            ) { text(line) }
        }
    }
}

private fun fmtCoord(v: Float): String = fmt2(v)
