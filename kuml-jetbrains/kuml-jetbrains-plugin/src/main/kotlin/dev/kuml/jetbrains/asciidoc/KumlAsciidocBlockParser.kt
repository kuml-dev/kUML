package dev.kuml.jetbrains.asciidoc

import dev.kuml.jetbrains.KumlPreviewSettings

/**
 * Pure text parser for kUML blocks inside AsciiDoc documents.
 *
 * Recognises two source forms (ported from the CLI-side extractor, without its
 * module dependency):
 *
 *  1. Listing block: `[source,kuml,…]` header + `----` fences.
 *  2. Block macro: `kuml::relative/path.kuml.kts[…]`.
 */
internal object KumlAsciidocBlockParser {
    private val LISTING_HEADER = Regex("""^\s*\[source\s*,\s*kuml(?:\s*,\s*([^\]]*))?\s*\]\s*$""")
    private val LISTING_FENCE = Regex("""^\s*----\s*$""")
    private val BLOCK_MACRO = Regex("""^\s*kuml::([^\s\[\]]+)\[([^\]]*)\]\s*$""")
    private val ATTR_PAIR = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*([^,\s]+)""")

    /**
     * Parses all kUML blocks from [asciidoc], in document order.
     */
    fun parse(asciidoc: String): List<KumlAsciidocBlock> {
        val lines = asciidoc.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<KumlAsciidocBlock>()
        var i = 0
        while (i < lines.size) {
            // Block macro has highest priority — single line.
            BLOCK_MACRO.matchEntire(lines[i])?.let { m ->
                val path = m.groupValues[1]
                val attrs = parseAttributes(m.groupValues[2])
                result +=
                    KumlAsciidocBlock(
                        kind = KumlAsciidocBlock.Kind.BLOCK_MACRO,
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
                    // Expect `----` on the next non-blank line.
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j >= lines.size || !LISTING_FENCE.matches(lines[j])) {
                        // No listing fence → ignore, continue
                        i++
                        return@let
                    }
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
                        KumlAsciidocBlock(
                            kind = KumlAsciidocBlock.Kind.LISTING,
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

    fun parseAttributes(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        ATTR_PAIR.findAll(raw).forEach { m ->
            val key = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    /**
     * Resolves `theme` from [attributes], falling back to the global preview setting
     * when absent or invalid (mirrors Markdown fence behaviour).
     */
    fun resolveTheme(attributes: Map<String, String>): String =
        attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()

    /**
     * Resolves `name` from [attributes], falling back to [defaultName].
     */
    fun resolveName(
        attributes: Map<String, String>,
        defaultName: String = "asciidoc-diagram",
    ): String = attributes["name"] ?: defaultName

    /**
     * Resolves optional `width` attribute (raw string; may be digits or CSS length).
     */
    fun resolveWidth(attributes: Map<String, String>): String? = attributes["width"]
}

/**
 * A single kUML site in an AsciiDoc document — either an inline listing or a block macro.
 */
internal data class KumlAsciidocBlock(
    val kind: Kind,
    val source: String,
    val targetPath: String?,
    val startLine: Int,
    val endLine: Int,
    val attributes: Map<String, String>,
) {
    enum class Kind { LISTING, BLOCK_MACRO }
}
