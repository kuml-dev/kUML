package dev.kuml.asciidoc

/**
 * Eine einzelne kUML-Stelle in einem AsciiDoc-Dokument — entweder ein
 * `[source,kuml]`-Listing-Block mit inline-Script oder ein `kuml::path[]`-
 * Block-Makro, das ein externes `*.kuml.kts`-Skript referenziert.
 *
 * @property kind Unterscheidet die beiden Quellformen.
 * @property source Roher Script-Quelltext. Bei Block-Makros wird er beim Erzeugen
 *   bereits aus der referenzierten Datei gelesen.
 * @property targetPath Pfad-String beim Block-Makro (z.B. `"diagrams/login.kuml.kts"`),
 *   `null` beim Listing-Block.
 * @property startLine 1-basierte Zeile des ersten Bestandteils des Blocks.
 * @property endLine 1-basierte Zeile des letzten Bestandteils des Blocks (inklusive).
 * @property attributes Optionale Attribute aus dem AsciiDoc-Block-Header bzw. den
 *   eckigen Klammern des Block-Makros, z.B. `name=foo,width=800`.
 */
public data class AsciidocKumlBlock(
    public val kind: AsciidocBlockKind,
    public val source: String,
    public val targetPath: String? = null,
    public val startLine: Int,
    public val endLine: Int,
    public val attributes: Map<String, String> = emptyMap(),
) {
    public val name: String? get() = attributes["name"]
    public val width: Int? get() = attributes["width"]?.toIntOrNull()
}

/** Welche AsciiDoc-Syntax den Block geliefert hat. */
public enum class AsciidocBlockKind {
    /** `[source,kuml]\n----\n…\n----` listing block. */
    LISTING,

    /** `kuml::path/to/file.kuml.kts[…]` block macro. */
    BLOCK_MACRO,
}
