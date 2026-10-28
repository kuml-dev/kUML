package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.Sysml2ReqCard
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.TestCaseDefinition

/**
 * Rendert eine SysML-2-[RequirementDefinition] als dreikompartimentige
 * Anforderungs-Box (V2.0.8).
 *
 * Visuell:
 *  - Kompartiment 1: Stereotyp `«requirement»`, zentriert.
 *  - Kompartiment 2: Name — wenn [RequirementDefinition.reqId] gesetzt ist,
 *    als `R-001 :: TopSpeedRequirement` prefixiert; sonst nur der Name.
 *  - Kompartiment 3: der Anforderungstext aus [RequirementDefinition.text],
 *    word-gewrappt auf ~25 Zeichen pro Zeile. Leerer Text ⇒ Kompartiment
 *    entfällt komplett (kein Divider, keine leere Zeile).
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class`,
 * `kuml-stereotype`, `kuml-title`, `kuml-body`, `kuml-divider`), damit die
 * REQ-Boxen visuell mit BDD/IBD-Boxen im selben Diagramm harmonieren —
 * Tooling-Konsumenten brauchen keine Spezial-Stylesheets.
 *
 * V2.x:
 *  - Subject-Kompartment mit Anzeige des `subject`-Feldes als zusätzliche
 *    Zeile in der Box.
 *  - Word-Wrap mit echter Glyph-Breiten-Messung statt Zeichenanzahl.
 *  - Constraint-Expression-Kompartment (formale OCL/Typed-Expression-Sicht
 *    der Anforderung).
 */
internal fun renderSysml2Requirement(
    element: RequirementDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    // Wave C1: the card geometry and the wrapped lines come from the shared Sysml2ReqCard so that
    // the box the layout bridge reserved matches exactly what is drawn here. Titles are wrapped
    // (never truncated); user text is length-capped defensively inside Sysml2ReqCard.
    val card = Sysml2ReqCard.forRequirement(element)
    renderCard(card = card, id = element.id, isAbstract = element.isAbstract, layout = layout, builder = builder)
}

/** Renders a [TestCaseDefinition] as a rounded verification box with the `«testCase»` stereotype (wave C1). */
internal fun renderSysml2TestCase(
    element: TestCaseDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val card = Sysml2ReqCard.forTestCase(element)
    renderCard(card = card, id = element.id, isAbstract = element.isAbstract, layout = layout, builder = builder, cornerRadius = 8f)
}

private fun renderCard(
    card: Sysml2ReqCard.Card,
    id: String,
    isAbstract: Boolean,
    layout: NodeLayout,
    builder: SvgBuilder,
    cornerRadius: Float = 0f,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        val rectAttrs =
            buildMap {
                put("width", fmt(w))
                put("height", fmt(h))
                put("class", "kuml-class")
                if (cornerRadius > 0f) {
                    put("rx", fmt(cornerRadius))
                    put("ry", fmt(cornerRadius))
                }
                // Assumption: dashed border.
                if (card.isAssumption) put("stroke-dasharray", "4 3")
            }
        tag(name = "rect", attrs = rectAttrs)

        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-stereotype",
                    "x" to fmt(w / 2f),
                    "y" to fmt(Sysml2ReqCard.STEREOTYPE_BASELINE),
                    "text-anchor" to "middle",
                    "font-size" to "9",
                ),
        ) { text(card.stereotype) }

        val nameClass = if (isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        card.titleLines.forEachIndexed { i, line ->
            val nameAttrs =
                buildMap<String, String> {
                    put("class", nameClass)
                    put("x", fmt(w / 2f))
                    put("y", fmt(Sysml2ReqCard.TITLE_FIRST_BASELINE + i * Sysml2ReqCard.TITLE_LINE_H))
                    put("text-anchor", "middle")
                    put("font-size", "12")
                    put("font-weight", "bold")
                    if (isAbstract) put("font-style", "italic")
                }
            tag(name = "text", attrs = nameAttrs) { text(line) }
        }

        card.dividerY?.let { dy ->
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to "0",
                        "y1" to fmt(dy),
                        "x2" to fmt(w),
                        "y2" to fmt(dy),
                        "class" to "kuml-divider",
                    ),
            )
        }
        card.bodyLines.forEachIndexed { i, line ->
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(Sysml2ReqCard.H_PAD),
                        "y" to fmt(card.bodyFirstBaseline + i * Sysml2ReqCard.BODY_LINE_H),
                        "font-size" to "11",
                    ),
            ) { text(line) }
        }
    }
}

/**
 * Einfache Word-Wrap-Heuristik (nur noch für Kanten-Labels; Karten nutzen [Sysml2ReqCard.wrap]): Splittet [text] an Wortgrenzen, sodass jede
 * resultierende Zeile höchstens [maxChars] Zeichen lang ist. Ein einzelnes
 * Wort, das länger als [maxChars] ist, landet allein in einer Zeile.
 *
 * V2.x: echte Glyph-Breiten-Messung über die Theme-Font-Metriken — das hier
 * ist die V2.0.8-MVP-Approximation.
 */
internal fun wrapWords(
    text: String,
    maxChars: Int,
): List<String> {
    if (text.isEmpty()) return emptyList()
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= maxChars) {
            current.append(' ').append(word)
        } else {
            lines += current.toString()
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}

private fun fmt(v: Float): String = fmt3(v)
