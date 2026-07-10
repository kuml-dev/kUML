package dev.kuml.langsupport.rename

/**
 * Pure-Kotlin helper that locates all rename candidates for a given DSL element name
 * inside a `.kuml.kts` script text.
 *
 * No IntelliJ Platform dependency — fully testable in plain Kotest.
 * `KumlRenameHandler` (in the kuml-jetbrains module) delegates to this object
 * for the analysis step.
 *
 * ## Algorithm
 *
 * 1. Blank/empty guards — returns `emptyList()` immediately.
 * 2. Comments are masked (content replaced with spaces, length preserved, newlines kept)
 *    so that occurrences inside comments are not reported.
 * 3. String literals in the masked text are matched by looking for `"<name>"` patterns
 *    ([Kind.STRING_LITERAL]).  The reported offset points to the first character of
 *    `<name>` (i.e. the opening quote is **not** included in the candidate).
 * 4. The masked text is then further processed by masking string contents, and the
 *    result is scanned for whole-word occurrences of `<name>` that are not inside a
 *    string literal ([Kind.VARIABLE_REF]).
 * 5. Candidates are deduplicated by offset and sorted ascending.
 *
 * V2.0.41
 */
public object KumlRenameExtractor {
    public enum class Kind { STRING_LITERAL, VARIABLE_REF }

    public data class Candidate(
        val offset: Int,
        val length: Int,
        val kind: Kind,
    ) {
        public val endOffset: Int get() = offset + length
        public val range: IntRange get() = offset until endOffset
    }

    public fun findRenameCandidates(
        text: String,
        name: String,
    ): List<Candidate> {
        if (text.isBlank() || name.isBlank()) return emptyList()

        val maskedText = maskComments(text)
        val results = mutableListOf<Candidate>()

        // 1. String literals: find "name" (with quotes) — report offset of name inside quotes
        val literalRegex = Regex(""""(${Regex.escape(name)})"""")
        for (match in literalRegex.findAll(maskedText)) {
            // group(1) is the name inside the quotes
            val nameOffset = match.groups[1]!!.range.first
            results += Candidate(nameOffset, name.length, Kind.STRING_LITERAL)
        }

        // 2. Variable refs: scan text with strings masked to avoid false positives
        val maskedNoStrings = maskStrings(maskedText)
        val varRefRegex = Regex("""(?<![\w$])(${Regex.escape(name)})(?![\w$])""")
        for (match in varRefRegex.findAll(maskedNoStrings)) {
            val nameOffset = match.groups[1]!!.range.first
            results += Candidate(nameOffset, name.length, Kind.VARIABLE_REF)
        }

        return results.distinctBy { it.offset }.sortedBy { it.offset }
    }

    /**
     * Replaces comment content with spaces while preserving the overall length and
     * all newline characters.
     *
     * Line comments (`// ... <newline>`) and block comments (`/* ... */`) are handled.
     */
    internal fun maskComments(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length) {
            if (i + 1 < sb.length && sb[i] == '/' && sb[i + 1] == '/') {
                // Line comment: replace until end of line
                i += 2
                while (i < sb.length && sb[i] != '\n') {
                    sb[i] = ' '
                    i++
                }
                // Leave the '\n' in place
            } else if (i + 1 < sb.length && sb[i] == '/' && sb[i + 1] == '*') {
                // Block comment: replace until closing */
                sb[i] = ' '
                sb[i + 1] = ' '
                i += 2
                while (i < sb.length) {
                    if (i + 1 < sb.length && sb[i] == '*' && sb[i + 1] == '/') {
                        sb[i] = ' '
                        sb[i + 1] = ' '
                        i += 2
                        break
                    } else {
                        if (sb[i] != '\n') sb[i] = ' '
                        i++
                    }
                }
            } else if (sb[i] == '"') {
                // Skip over string literals to avoid treating `//` inside a string as comment
                i++
                while (i < sb.length && sb[i] != '"') {
                    if (sb[i] == '\\') i++ // skip escaped character
                    i++
                }
                if (i < sb.length) i++ // closing quote
            } else {
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Replaces the *contents* of string literals (between the quotes) with spaces,
     * keeping the quotes themselves and preserving overall length.
     *
     * This prevents [findRenameCandidates] from reporting [Kind.VARIABLE_REF] matches
     * for names that only appear inside string literals.
     */
    internal fun maskStrings(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length) {
            if (sb[i] == '"') {
                i++ // skip opening quote
                while (i < sb.length && sb[i] != '"') {
                    if (sb[i] == '\\') {
                        // Keep the backslash and the escaped char as-is so length is stable
                        i += 2
                        continue
                    }
                    sb[i] = ' '
                    i++
                }
                if (i < sb.length) i++ // skip closing quote
            } else {
                i++
            }
        }
        return sb.toString()
    }
}
