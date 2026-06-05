package dev.kuml.asciidoc

/**
 * Findet kUML-Stellen in einem AsciiDoc-Dokument.
 *
 * Zwei Quellformen werden erkannt:
 *
 *  1. **Listing-Block** mit `kuml`-Sprachattribut:
 *     ```
 *     [source,kuml]
 *     ----
 *     classDiagram(name = "Demo") { ... }
 *     ----
 *     ```
 *     Optionale Attribute: `[source,kuml,name="hello",width=800]`.
 *
 *  2. **Block-Makro** `kuml::path[…]`:
 *     ```
 *     kuml::diagrams/login.kuml.kts[width=800]
 *     ```
 *     Der Pfad ist relativ zum AsciiDoc-Wurzelverzeichnis (vom Aufrufer aufgelöst).
 *
 * Der Extractor führt **keine** Skripte aus und liest **keine** referenzierten
 * Dateien — er reicht beim Block-Makro nur den Pfad-String durch. Dateilesen
 * passiert in [AsciidocProcessor.process].
 */
public object AsciidocBlockExtractor {
    private val LISTING_HEADER = Regex("""^\s*\[source\s*,\s*kuml(?:\s*,\s*([^\]]*))?\s*\]\s*$""")
    private val LISTING_FENCE = Regex("""^\s*----\s*$""")
    private val BLOCK_MACRO = Regex("""^\s*kuml::([^\s\[\]]+)\[([^\]]*)\]\s*$""")
    private val ATTR_PAIR = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*([^,\s]+)""")

    /** Extrahiert alle kUML-Stellen, in Dokument-Reihenfolge. */
    public fun extract(asciidoc: String): List<AsciidocKumlBlock> {
        val lines = asciidoc.split('\n')
        val result = mutableListOf<AsciidocKumlBlock>()
        var i = 0
        while (i < lines.size) {
            // Block-Makro hat höchste Priorität — single line.
            BLOCK_MACRO.matchEntire(lines[i])?.let { m ->
                val path = m.groupValues[1]
                val attrs = parseAttributes(m.groupValues[2])
                result +=
                    AsciidocKumlBlock(
                        kind = AsciidocBlockKind.BLOCK_MACRO,
                        source = "",
                        targetPath = path,
                        startLine = i + 1,
                        endLine = i + 1,
                        attributes = attrs,
                    )
                i++
                return@let
            } ?: run {
                LISTING_HEADER.matchEntire(lines[i])?.let { hdr ->
                    val attrs = parseAttributes(hdr.groupValues.getOrNull(1) ?: "")
                    val headerLine = i + 1
                    // Erwarte `----` in der nächsten Zeile (eine Leerzeile dazwischen ist auch ok).
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j >= lines.size || !LISTING_FENCE.matches(lines[j])) {
                        // Kein Listing-Fence → ignorieren, weiter
                        i++
                        return@let
                    }
                    val openFenceLine = j
                    var k = j + 1
                    val buf = StringBuilder()
                    while (k < lines.size && !LISTING_FENCE.matches(lines[k])) {
                        buf.append(lines[k])
                        if (k < lines.size - 1) buf.append('\n')
                        k++
                    }
                    val source = buf.toString().trimEnd('\n')
                    val closeFenceLine = k.coerceAtMost(lines.size - 1)
                    result +=
                        AsciidocKumlBlock(
                            kind = AsciidocBlockKind.LISTING,
                            source = source,
                            targetPath = null,
                            startLine = headerLine,
                            endLine = closeFenceLine + 1,
                            attributes = attrs,
                        )
                    i = closeFenceLine + 1
                    return@let
                } ?: run {
                    i++
                }
            }
        }
        return result
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        ATTR_PAIR.findAll(raw).forEach { m ->
            val key = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }
}
