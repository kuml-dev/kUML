package dev.kuml.io.svg

/**
 * Mini-DSL für die Konstruktion von SVG-Markup.
 *
 * Erzeugt wohlgeformtes XML mit optionalem Pretty-Print. Alle Text-Inhalte
 * werden automatisch XML-escaped (`<`, `>`, `&`, `"`, `'`).
 *
 * Beispiel:
 * ```kotlin
 * val builder = SvgBuilder(pretty = true)
 * builder.tag("rect", mapOf("width" to "100", "height" to "50"))
 * println(builder.toString()) // <rect width="100" height="50"/>
 * ```
 */
internal class SvgBuilder(
    private val pretty: Boolean,
    private val indent: Int = 0,
) {
    private val sb = StringBuilder()

    /** Öffnet ein XML-Tag mit optionalen Attributen und einem optionalen Inhalt-Block. */
    fun tag(
        name: String,
        attrs: Map<String, String> = emptyMap(),
        block: (SvgBuilder.() -> Unit)? = null,
    ) {
        val attrStr =
            if (attrs.isEmpty()) {
                ""
            } else {
                " " + attrs.entries.joinToString(" ") { (k, v) -> """$k="${escapeAttr(v)}"""" }
            }
        if (block == null) {
            appendLine("<$name$attrStr/>")
        } else {
            appendLine("<$name$attrStr>")
            val child = SvgBuilder(pretty, indent + 2)
            child.block()
            sb.append(child.toString())
            appendLine("</$name>")
        }
    }

    /** Fügt XML-Text-Inhalt hinzu (wird XML-escaped). */
    fun text(content: String) {
        appendLine(escapeText(content))
    }

    /**
     * Fügt Raw-XML-Markup unverändert in den Builder ein.
     *
     * Der Aufrufer ist verantwortlich dafür, dass [markup] wohlgeformtes XML ist.
     * Wird für Mixed-Content wie `<tspan>…</tspan> plaintext` benötigt, das nicht
     * über [text] (escaped) oder [tag] (strukturiert) abgebildet werden kann.
     *
     * Typischer Use-Case: Stereotyp-Präfix als kursiver `<tspan>` vor dem
     * Feature-Namen in einer `<text>`-Zeile.
     */
    fun rawXml(markup: String) {
        appendLine(markup)
    }

    /** Fügt einen XML-Kommentar hinzu. */
    fun comment(text: String) {
        appendLine("<!-- ${text.replace("--", "- -")} -->")
    }

    /** Gibt den bisher gebauten SVG-String zurück. */
    override fun toString(): String = sb.toString()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun appendLine(line: String) {
        if (pretty) {
            sb.append(" ".repeat(indent))
        }
        sb.append(line)
        if (pretty) {
            sb.append("\n")
        }
    }

    private fun escapeText(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeAttr(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

/** XML-escaped einen String für die Verwendung in Attributwerten. */
internal fun xmlEscapeAttr(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/**
 * XML-escaped einen String für die Verwendung in [SvgBuilder.rawXml]-Kontexten,
 * **ausschließlich für Textinhalt** (zwischen öffnendem und schließendem Tag,
 * nie innerhalb eines Attributwerts).
 *
 * Escapiert `&`, `<`, `>` und `'`. Doppelte Anführungszeichen (`"`) werden
 * **nicht** escapiert, weil sie in XML-Textinhalt kein Sonderzeichen sind.
 *
 * **Warnung — kein Attribut-Einsatz:** Diese Funktion darf *nicht* innerhalb
 * von Attributwerten (z. B. `title="…"`) in einem `rawXml()`-Block verwendet
 * werden. Für Attributwerte ist [xmlEscapeAttr] vorgesehen, das zusätzlich `"`
 * escapiert. Ein Aufrufer, der `xmlEscapeContent()` in ein Attribut einsetzt,
 * erzeugt im Bestfall unvollständig escapiertes, im schlechtesten Fall injizier-
 * bares SVG.
 *
 * Wichtig: Nicht bei [SvgBuilder.text] verwenden — das würde doppeltes Escaping
 * erzeugen (`&amp;lt;` statt `&lt;`). [SvgBuilder.text] escapiert intern bereits.
 */
internal fun xmlEscapeContent(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
