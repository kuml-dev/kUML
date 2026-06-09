package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.RequirementDefinition

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
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val rawTitle =
        if (element.reqId.isNotEmpty()) "${element.reqId} :: ${element.name}" else element.name
    val titleText = truncateTitle(rawTitle)
    val hasText = element.text.isNotEmpty()
    val textLines: List<String> = if (hasText) wrapWords(element.text, WRAP_WIDTH) else emptyList()

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 16f

        // Compartment 1 — `«requirement»`-Stereotyp.
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text("«requirement»") }
        cy += 14f

        // Compartment 2 — Name, optional mit `R-NNN ::`-Präfix.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        val nameAttrs =
            buildMap<String, String> {
                put("class", nameClass)
                put("x", fmt(w / 2f))
                put("y", fmt(cy))
                put("text-anchor", "middle")
                if (element.isAbstract) put("font-style", "italic")
            }
        tag("text", nameAttrs) { text(titleText) }
        cy += 12f

        // Compartment 3 — Anforderungstext (nur wenn vorhanden).
        if (hasText) {
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 14f

            for (line in textLines) {
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "6", "y" to fmt(cy)),
                ) { text(line) }
                cy += 13f
            }
        }
    }
}

/**
 * Einfache Word-Wrap-Heuristik: Splittet [text] an Wortgrenzen, sodass jede
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

private const val WRAP_WIDTH = 25

private const val REQ_TITLE_MAX_CHARS = 35

private fun truncateTitle(s: String): String = if (s.length <= REQ_TITLE_MAX_CHARS) s else s.substring(0, REQ_TITLE_MAX_CHARS - 1) + "…"

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
