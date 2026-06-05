package dev.kuml.cli

/**
 * Idempotent text formatter for `*.kuml.kts` scripts.
 *
 * Applies the following normalisation rules (in order):
 * 1. Trailing whitespace removed from each line.
 * 2. Leading tabs converted to 4-space indentation.
 * 3. Consecutive blank lines collapsed to at most one.
 * 4. File ends with exactly one newline; trailing blank lines removed.
 *
 * The formatter operates on raw source text — no script evaluation is performed.
 * Comments and non-DSL Kotlin code are preserved verbatim (modulo whitespace).
 *
 * Idempotency: `format(format(source)) == format(source)` for all inputs.
 */
internal object KumlFormatter {
    internal fun format(source: String): String {
        val lines = source.split("\n")
        val result = mutableListOf<String>()
        var consecutiveBlanks = 0

        for (line in lines) {
            // 1. Remove trailing whitespace
            val trimmed = line.trimEnd()
            // 2. Expand leading tabs (tab stop = 4)
            val expanded = expandLeadingTabs(trimmed)
            // 3. Collapse consecutive blank lines
            if (expanded.isBlank()) {
                consecutiveBlanks++
                if (consecutiveBlanks <= 1) result += ""
            } else {
                consecutiveBlanks = 0
                result += expanded
            }
        }

        // 4. Remove trailing blank lines; ensure single trailing newline
        while (result.isNotEmpty() && result.last().isBlank()) result.removeLast()
        return if (result.isEmpty()) "\n" else result.joinToString("\n") + "\n"
    }

    /** Converts leading tab characters to 4-space sequences (tab stop = 4). */
    private fun expandLeadingTabs(line: String): String {
        if (!line.startsWith('\t')) return line
        val indent = line.takeWhile { it == '\t' }
        val rest = line.removePrefix(indent)
        return "    ".repeat(indent.length) + rest
    }
}
