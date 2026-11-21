package dev.kuml.workspace

/**
 * Byte-preserving rewrite of an OKF alias `type:` value to its canonical id (Nacharbeit 114).
 *
 * This is a pure building block. It is wired into [OkfAliasRewriter] and the CLI (`kuml workspace validate
 * --fix-aliases`); the Desktop uses it for the buffer-level one-click action
 * (Nacharbeit 122b); the language server derives its quick-fix edit from [typeValueSpan]
 * (Nacharbeit 125).
 * It works on the raw text (no line-ending normalisation, no re-serialisation), so spacing, comments,
 * quotes and `\r\n` line endings survive; only the alias characters change.
 */
public object OkfAliasFix {
    private const val FENCE = "---"

    private class Hit(
        val lineIndex: Int,
        val valueStart: Int,
        val valueEnd: Int,
    )

    /**
     * Location of the `type:` value inside the frontmatter (quotes excluded).
     *
     * @property line 0-based line index (split on `\n` only, so a `\r` of CRLF stays on the line).
     * @property startColumn UTF-16 start column of the value within the line.
     * @property endColumn UTF-16 end column (exclusive).
     * @property value the raw value text.
     */
    public data class TypeValueSpan(
        val line: Int,
        val startColumn: Int,
        val endColumn: Int,
        val value: String,
    )

    /** Locates the `type:` value (last `type:` line wins), or `null` without frontmatter / `type:`. */
    public fun typeValueSpan(markdown: String): TypeValueSpan? {
        val lines = markdown.split('\n')
        val hit = findTypeValue(lines) ?: return null
        return TypeValueSpan(
            line = hit.lineIndex,
            startColumn = hit.valueStart,
            endColumn = hit.valueEnd,
            value = lines[hit.lineIndex].substring(hit.valueStart, hit.valueEnd),
        )
    }

    /** Returns the rewritten text, or `null` when nothing must change (no frontmatter, no `type:`, canonical or unknown id). */
    public fun canonicalizeType(markdown: String): String? {
        val fm = FrontmatterParser.parse(markdown)
        if (!fm.present) return null
        val alias = fm.type ?: return null
        if (!OkfType.isAlias(alias)) return null
        val canonical = OkfType.canonicalIdFor(alias)
        if (canonical == alias) return null

        val lines = markdown.split('\n')
        val hit = findTypeValue(lines) ?: return null
        val offset = lines.take(hit.lineIndex).sumOf { it.length + 1 }
        val start = offset + hit.valueStart
        val end = offset + hit.valueEnd
        if (markdown.substring(start, end) != alias) return null
        return markdown.substring(0, start) + canonical + markdown.substring(end)
    }

    /** 1-based line number of the `type:` field inside the frontmatter, or `null`. */
    public fun typeLineNumber(markdown: String): Int? {
        val lines = markdown.split('\n')
        return findTypeValue(lines)?.let { it.lineIndex + 1 }
    }

    /** Locates the value of the last `type:` line (matching the parser, where later keys win). */
    private fun findTypeValue(lines: List<String>): Hit? {
        if (lines.isEmpty() || lines[0].trim() != FENCE) return null
        val closing = (1 until lines.size).firstOrNull { lines[it].trim() == FENCE } ?: return null
        var hit: Hit? = null
        for (i in 1 until closing) {
            val raw = lines[i]
            val colon = raw.indexOf(':')
            if (colon < 0 || raw.substring(0, colon).trim() != "type") continue
            var s = colon + 1
            var e = raw.length
            while (s < e && raw[s].isWhitespace()) s++
            while (e > s && raw[e - 1].isWhitespace()) e--
            if (e - s >= 2 && (raw[s] == '"' && raw[e - 1] == '"' || raw[s] == '\'' && raw[e - 1] == '\'')) {
                s++
                e--
            }
            if (s < e) hit = Hit(lineIndex = i, valueStart = s, valueEnd = e)
        }
        return hit
    }
}
