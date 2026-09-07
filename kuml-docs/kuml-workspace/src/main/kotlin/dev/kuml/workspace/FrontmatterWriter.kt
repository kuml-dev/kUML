package dev.kuml.workspace

private const val FENCE = "---"

/**
 * Chirurgical, line-level edits to an OKF Markdown document's YAML frontmatter block
 * (Alan Kay's projection principle: the buffer is the model, the formular writes a
 * splice into that same buffer — never a full re-serialisation).
 *
 * Every function here treats [FrontmatterParser]'s exact fence-detection rule as the
 * single source of truth for "is there a frontmatter block at all" (a leading `---`
 * line, followed somewhere by a closing `---` line) — anything outside that grammar
 * (comments, unknown keys, key order, the document body) is left byte-identical.
 */
public object FrontmatterWriter {
    /** One source line plus the exact separator that followed it (`""` for the last line when [markdown] has no trailing newline). */
    private data class Line(
        val content: String,
        val separator: String,
    )

    /**
     * Sets `key: value` in the leading `---`…`---` block of [markdown] and returns the result.
     *
     * - An existing `key:` line: only the value part (after the colon) is replaced —
     *   the key's own leading indentation and position in the block are preserved.
     * - A missing key: a new `key: value` line is inserted immediately before the closing
     *   `---`.
     * - No frontmatter block at all ([FrontmatterParser.parse]'s `present == false`): a
     *   new block is created at the very top of the document; the original content
     *   (including a stray unterminated `---` line, if that's *why* it was absent)
     *   becomes the body, byte-identical.
     *
     * [value] is escaped via [escapeScalar] before being written — callers always pass the
     * raw, human-meaning value (e.g. a title containing a colon or a quote), never a
     * pre-quoted YAML scalar. Comments, unknown keys, key order, the document body, and a
     * missing trailing newline are all preserved.
     *
     * Every line's own separator (LF or CRLF) is preserved individually (bugfix, review
     * finding) — this used to pick ONE separator for the whole document by checking whether
     * `markdown` contained any `"\r\n"` ANYWHERE, then rewrote every line with it. A single
     * CRLF sequence embedded in the body (e.g. a code block pasted from Windows) used to
     * flip an otherwise all-LF document entirely to CRLF on every `setField` call, showing
     * as a whole-file diff instead of a one-line change. A brand-new line (a missing key, or
     * a whole new frontmatter block) takes the separator of the line it is inserted next to,
     * so the new line matches the frontmatter block's own style rather than some unrelated
     * separator found elsewhere in the body.
     */
    public fun setField(
        markdown: String,
        key: String,
        value: String,
    ): String {
        val escaped = escapeScalar(value)
        val lines = splitKeepingSeparators(markdown)

        val bounds = fenceBounds(lines)
        if (bounds == null) {
            val newSep = lines.firstOrNull()?.separator?.takeIf { it.isNotEmpty() } ?: "\n"
            val newLines =
                mutableListOf(
                    Line(content = FENCE, separator = newSep),
                    Line(content = "$key: $escaped", separator = newSep),
                    Line(content = FENCE, separator = newSep),
                )
            newLines.addAll(lines)
            return joinLines(newLines)
        }
        val (openIdx, closeIdx) = bounds

        val foundIndex = indexOfKeyLine(lines = lines, openIdx = openIdx, closeIdx = closeIdx, key = key)
        if (foundIndex >= 0) {
            val raw = lines[foundIndex]
            val colonIdx = raw.content.indexOf(':')
            val prefix = raw.content.substring(0, colonIdx + 1)
            lines[foundIndex] = raw.copy(content = "$prefix $escaped")
        } else {
            val insertSep =
                lines[closeIdx].separator.takeIf { it.isNotEmpty() }
                    ?: lines.getOrNull(closeIdx - 1)?.separator?.takeIf { it.isNotEmpty() }
                    ?: "\n"
            lines.add(closeIdx, Line(content = "$key: $escaped", separator = insertSep))
        }
        return joinLines(lines)
    }

    /**
     * Removes the first `key:` line from the leading frontmatter block of [markdown].
     * No-op (returns [markdown] unchanged) when there is no frontmatter block, or the
     * block has no `key:` line.
     */
    public fun removeField(
        markdown: String,
        key: String,
    ): String {
        val lines = splitKeepingSeparators(markdown)

        val bounds = fenceBounds(lines) ?: return markdown
        val (openIdx, closeIdx) = bounds

        val foundIndex = indexOfKeyLine(lines = lines, openIdx = openIdx, closeIdx = closeIdx, key = key)
        if (foundIndex < 0) return markdown
        lines.removeAt(foundIndex)
        return joinLines(lines)
    }

    /**
     * Escapes [value] for safe embedding as a single-line YAML frontmatter scalar
     * understood by [FrontmatterParser]: an embedded newline is replaced with a space (a
     * raw line break would otherwise split into a bogus second frontmatter line), and the
     * value is wrapped in double quotes — with embedded backslashes/double quotes escaped —
     * whenever it is empty, contains a character (`:`, `#`, `"`) that
     * [FrontmatterParser]'s `key: value` split would otherwise misinterpret, has
     * leading/trailing whitespace that would otherwise be trimmed away, or itself starts
     * and ends with a single quote (`'…'`) — [FrontmatterParser.parse] strips a matching
     * outer single-quote pair unconditionally, so leaving such a value unquoted would lose
     * the quotes on the next parse.
     *
     * The single source of this rule in the repo — [OkfConverter.wrapAsOkf] delegates
     * here rather than duplicating it. [FrontmatterParser.parse] un-escapes `\"`/`\\`
     * inside a double-quoted scalar, so `escapeScalar` followed by a parse round-trips
     * byte-for-byte (see `FrontmatterWriterTest`'s round-trip cases).
     */
    public fun escapeScalar(value: String): String {
        val singleLine = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuoting =
            singleLine.isEmpty() ||
                singleLine.contains(':') ||
                singleLine.contains('#') ||
                singleLine.contains('"') ||
                singleLine.trim() != singleLine ||
                (singleLine.length >= 2 && singleLine.startsWith("'") && singleLine.endsWith("'"))
        if (!needsQuoting) return singleLine
        val escapedInner = singleLine.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escapedInner\""
    }

    /**
     * Splits [markdown] into a mutable list of [Line]s, each carrying the exact separator
     * (`"\n"` or `"\r\n"`) that followed it in the source — the last element has an empty
     * separator whenever [markdown] has no trailing newline. [joinLines] is the exact
     * inverse: reassembling every [Line] reproduces [markdown] byte-for-byte when nothing
     * was changed.
     */
    private fun splitKeepingSeparators(markdown: String): MutableList<Line> {
        val result = mutableListOf<Line>()
        var searchFrom = 0
        while (true) {
            val nl = markdown.indexOf('\n', searchFrom)
            if (nl < 0) {
                result.add(Line(content = markdown.substring(searchFrom), separator = ""))
                return result
            }
            val hasCr = nl > searchFrom && markdown[nl - 1] == '\r'
            val contentEnd = if (hasCr) nl - 1 else nl
            result.add(Line(content = markdown.substring(searchFrom, contentEnd), separator = if (hasCr) "\r\n" else "\n"))
            searchFrom = nl + 1
        }
    }

    /** Exact inverse of [splitKeepingSeparators]. */
    private fun joinLines(lines: List<Line>): String =
        buildString {
            lines.forEach {
                append(it.content)
                append(it.separator)
            }
        }

    /** (openIdx, closeIdx) line indices of the frontmatter fence, or `null` — mirrors [FrontmatterParser]'s rule exactly. */
    private fun fenceBounds(lines: List<Line>): Pair<Int, Int>? {
        if (lines.isEmpty() || lines[0].content.trim() != FENCE) return null
        val closingIndex = lines.drop(1).indexOfFirst { it.content.trim() == FENCE }
        if (closingIndex < 0) return null
        return 0 to (closingIndex + 1)
    }

    /**
     * Finds the line index of the top-level `key:` scalar within `lines[openIdx+1 until
     * closeIdx]`, or `-1`. Skips `tags:` block-list continuation lines (`- value`) exactly
     * like [FrontmatterParser] does, so a tag value that happens to contain a colon (e.g.
     * `- resource: foo`) is never mistaken for a top-level key.
     */
    private fun indexOfKeyLine(
        lines: List<Line>,
        openIdx: Int,
        closeIdx: Int,
        key: String,
    ): Int {
        var pendingListKey: String? = null
        for (i in (openIdx + 1) until closeIdx) {
            val raw = lines[i].content
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (pendingListKey == "tags" && trimmed.startsWith("- ")) continue
            pendingListKey = null

            val colonIdx = raw.indexOf(':')
            if (colonIdx < 0) continue
            val lineKey = raw.substring(0, colonIdx).trim()
            if (lineKey.isEmpty()) continue
            val lineValue = raw.substring(colonIdx + 1).trim()
            if (lineValue.isEmpty() && lineKey == "tags") pendingListKey = "tags"

            if (lineKey == key) return i
        }
        return -1
    }
}
