package dev.kuml.cli.workspace

/**
 * Parsed YAML frontmatter of an OKF Markdown document (ADR-0011).
 *
 * Only a small subset of YAML is understood — see [FrontmatterParser] for the
 * exact grammar. This is intentional: OKF frontmatter in practice only ever
 * uses scalar `key: value` pairs plus a `tags` list, so a full YAML parser
 * (and the corresponding new dependency) would be over-engineering for FT-1.
 *
 * @property fields Scalar `key: value` pairs (e.g. `type`, `title`, `description`,
 *  `resource`, `timestamp`). Values are raw strings, unquoted.
 * @property tags Values of the `tags:` list, in document order. Supports both the
 *  inline form (`tags: [a, b]`) and the block form (`tags:` followed by `- a` lines).
 * @property present Whether a leading `---`…`---` frontmatter block was found at all.
 * @property bodyStartLine 1-based line number where the document body begins
 *  (the line after the closing `---`). Equals `1` when [present] is `false`.
 */
internal data class Frontmatter(
    val fields: Map<String, String>,
    val tags: List<String>,
    val present: Boolean,
    val bodyStartLine: Int,
) {
    /** Convenience accessor for the `type:` field — the OKF-vocabulary key (see [OkfType]). */
    val type: String? get() = fields["type"]

    /** Convenience accessor for the `title:` field. */
    val title: String? get() = fields["title"]
}

/**
 * Lenient, dependency-free parser for the small YAML subset used by OKF frontmatter.
 *
 * Recognised grammar:
 * - The document must start with a line that is exactly `---` to have frontmatter at all.
 *   Anything else means [Frontmatter.present] is `false` and the whole input is body.
 * - Scalar lines: `key: value` — split on the *first* `:` only, so values containing
 *   colons (e.g. ISO timestamps `2026-06-16T10:00:00Z`) are preserved intact.
 * - Inline list: `tags: [a, b, c]` — comma-separated, brackets stripped, entries trimmed.
 * - Block list: a `tags:` line (no inline value) followed by one or more `- value` lines.
 * - Surrounding double or single quotes on scalar values are stripped.
 * - Blank lines and lines that match neither pattern are ignored (lenient — this parser
 *   never throws on unrecognised input).
 * - The block ends at the next line that is exactly `---`. If no closing `---` is found,
 *   the entire input is treated as having no frontmatter ([Frontmatter.present] = `false`).
 */
internal object FrontmatterParser {
    private const val FENCE = "---"

    fun parse(markdown: String): Frontmatter {
        val lines = markdown.split('\n')
        if (lines.isEmpty() || lines[0].trim() != FENCE) {
            return Frontmatter(fields = emptyMap(), tags = emptyList(), present = false, bodyStartLine = 1)
        }

        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == FENCE }
        if (closingIndex < 0) {
            // No closing fence — treat as absent rather than guessing.
            return Frontmatter(fields = emptyMap(), tags = emptyList(), present = false, bodyStartLine = 1)
        }
        val closingLine = closingIndex + 1 // index within `lines`, 0-based

        val fields = mutableMapOf<String, String>()
        val tags = mutableListOf<String>()
        var pendingListKey: String? = null

        for (i in 1 until closingLine) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            val trimmed = raw.trim()

            if (pendingListKey == "tags" && trimmed.startsWith("- ")) {
                tags += unquote(trimmed.removePrefix("- ").trim())
                continue
            }
            pendingListKey = null

            val colonIdx = raw.indexOf(':')
            if (colonIdx < 0) continue
            val key = raw.substring(0, colonIdx).trim()
            if (key.isEmpty()) continue
            val value = raw.substring(colonIdx + 1).trim()

            if (value.isEmpty()) {
                // Could be the start of a block list (`tags:` followed by `- a` lines).
                if (key == "tags") pendingListKey = "tags"
                continue
            }

            if (key == "tags" && value.startsWith("[") && value.endsWith("]")) {
                val inner = value.substring(1, value.length - 1)
                inner
                    .split(',')
                    .map { unquote(it.trim()) }
                    .filter { it.isNotEmpty() }
                    .forEach { tags += it }
                continue
            }

            fields[key] = unquote(value)
        }

        val bodyStartLine = closingLine + 2 // 1-based line after the closing fence
        return Frontmatter(fields = fields, tags = tags, present = true, bodyStartLine = bodyStartLine)
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
        ) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
