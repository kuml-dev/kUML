package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.StereotypeTheme
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Stereotypable
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlNamedElement

/**
 * Hilfsfunktionen für das Stereotyp-Rendering im SVG-Renderer.
 *
 * Alle Methoden sind pure-Kotlin und Compose-unabhängig — sie können ohne
 * Lauf-Zeit-Kontext aufgerufen werden.
 *
 * Generisches Muster (kein 25-fach-when):
 * ```kotlin
 * val header = (element as? Stereotypable)?.stereotypeHeader(theme.stereotypes)
 * ```
 */
internal object StereotypeHelper {
    /**
     * Gibt die formatierte Headerzeile `«A, B»` zurück, oder `null` wenn keine
     * Stereotypen angewendet sind.
     *
     * Mehrfach-Stereotype werden mit [StereotypeTheme.joinSeparator] verbunden.
     */
    fun headerLabel(
        element: Stereotypable,
        theme: StereotypeTheme,
    ): String? {
        // V2.0.44: Combine applied (typed) stereotypes from profiles with the
        // simple `stereotypes: List<String>` field on `UmlNamedElement`. Either
        // alone produces a header; both together produce one combined header.
        // Previously only `appliedStereotypes` were rendered, so a DSL like
        // `stereotypes += "service"` was silently dropped — observed on
        // docker/k8s component diagrams.
        val appliedNames = element.appliedStereotypes.map { it.stereotypeName }
        val plainNames =
            (element as? UmlNamedElement)?.stereotypes.orEmpty()
                .filter { it.isNotBlank() }
        val joined = (appliedNames + plainNames).distinct()
        if (joined.isEmpty()) return null
        return "«" + joined.joinToString(theme.joinSeparator) + "»"
    }

    /**
     * Rendert die Stereotyp-Headerzeile über den Klassenname in den [SvgBuilder].
     *
     * Gibt zurück, um wie viele Pixel `cy` (der laufende Y-Cursor) erhöht wurde.
     * Wenn keine Stereotypen vorhanden: gibt 0 zurück.
     */
    fun renderHeader(
        element: Stereotypable,
        theme: KumlTheme,
        builder: SvgBuilder,
        cx: Float,
        cy: Float,
    ): Float {
        val label = headerLabel(element, theme.stereotypes) ?: return 0f
        val fontSize = theme.stereotypes.headerFontSize
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(cx),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(label)) }
        return fontSize + 4f
    }

    /**
     * Rendert das Tagged-Value-Compartment (wenn [StereotypeTheme.showTaggedValues] aktiv).
     *
     * Jede Tagged-Value-Zeile hat die Form `{tag = value}`.
     * Gibt zurück, um wie viele Pixel `cy` erhöht wurde (0 wenn kein Compartment).
     */
    fun renderTaggedValues(
        element: Stereotypable,
        theme: KumlTheme,
        builder: SvgBuilder,
        w: Float,
        cy: Float,
    ): Float {
        if (!theme.stereotypes.showTaggedValues) return 0f
        val rows = buildTaggedValueRows(element.appliedStereotypes)
        if (rows.isEmpty()) return 0f

        val fontSize = theme.stereotypes.taggedValueFontSize
        val lineH = fontSize + 3f

        // Divider before compartment
        builder.tag(
            "line",
            mapOf(
                "x1" to "0",
                "y1" to fmt(cy),
                "x2" to fmt(w),
                "y2" to fmt(cy),
                "class" to "kuml-divider",
            ),
        )
        var cy2 = cy + lineH

        for (row in rows) {
            builder.tag(
                "text",
                mapOf(
                    "class" to "kuml-tagged-value",
                    "x" to "8",
                    "y" to fmt(cy2),
                ),
            ) { text(xmlEscapeText(row)) }
            cy2 += lineH
        }
        return cy2 - cy
    }

    /**
     * Rendert ein Stereotyp-Label an einer Edge als Mittelpunkt-Label.
     * Gibt zurück ob ein Label gerendert wurde.
     */
    fun renderEdgeStereotype(
        element: Stereotypable,
        theme: KumlTheme,
        builder: SvgBuilder,
        midX: Float,
        midY: Float,
    ): Boolean {
        val label = headerLabel(element, theme.stereotypes) ?: return false
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(midX),
                "y" to fmt(midY),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(label)) }
        return true
    }

    // ── Feature-level stereotype prefix ───────────────────────────────────────

    /**
     * Gibt den Stereotyp-Präfix als kursives `<tspan>` zurück, oder den leeren
     * String wenn keine Stereotypen gesetzt sind oder der Theme-Toggle
     * [dev.kuml.renderer.theme.core.StereotypeTheme.showFeatureStereotypes] deaktiviert ist.
     *
     * Der trailing Space hinter dem schließenden `</tspan>` ist Teil des Strings,
     * damit der nachfolgende Feature-Name visuell vom Präfix getrennt ist.
     *
     * Beispiel-Output für `stereotype("PersistenceContext")`:
     * ```
     * <tspan class="kuml-feature-stereotype" font-style="italic" font-size="9">«PersistenceContext»</tspan>
     * ```
     *
     * Der Aufrufer muss das Ergebnis via [SvgBuilder.rawXml] in einen `<text>`-Block
     * einfügen — **nicht** via [SvgBuilder.text], da [text] den Tspan-Markup escaped.
     */
    fun featureStereotypeTspan(
        element: Stereotypable,
        theme: KumlTheme,
    ): String {
        if (!theme.stereotypes.showFeatureStereotypes) return ""
        if (element.appliedStereotypes.isEmpty()) return ""
        val joined =
            element.appliedStereotypes
                .joinToString(theme.stereotypes.joinSeparator) { it.stereotypeName }
        val fontSize = theme.stereotypes.featureStereotypeFontSize.toInt()
        val label = xmlEscapeContent("«$joined»")
        return """<tspan class="kuml-feature-stereotype" font-style="italic" font-size="$fontSize">$label</tspan> """
    }

    // ── Tagged-value formatting ────────────────────────────────────────────────

    /**
     * Baut die Liste der `{tag = value}`-Zeilen aus allen angewendeten Stereotypen.
     * Stabile Reihenfolge: Stereotype in Deklarationsreihenfolge, Tags in map-Reihenfolge.
     */
    fun buildTaggedValueRows(applications: List<AppliedStereotype>): List<String> =
        applications.flatMap { app ->
            app.tags.entries.map { (k, v) -> "{$k = ${formatTagValue(v)}}" }
        }

    /** Formatiert einen [TagValue] zu einem lesbaren String. */
    fun formatTagValue(v: TagValue): String =
        when (v) {
            is TagValue.StringVal -> v.v
            is TagValue.IntVal -> v.v.toString()
            is TagValue.LongVal -> v.v.toString()
            is TagValue.DoubleVal -> v.v.toString()
            is TagValue.BoolVal -> v.v.toString()
            is TagValue.EnumVal -> v.valueName
            is TagValue.ListVal -> v.items.joinToString(", ", "[", "]") { formatTagValue(it) }
        }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }
}
