package dev.kuml.cli

import dev.kuml.runtime.chain.ModelHasher

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
 *
 * See also [canonical] for the stricter V3.0.1 canonical form used by [ModelHasher].
 */
internal object KumlFormatter {
    /**
     * Canonical normal form for deterministic model hashing (V3.0.1).
     *
     * Stricter than [format]: removes ALL blank lines (instead of collapsing to one)
     * and unifies line endings to LF. Produces exactly the normalised form over which
     * [ModelHasher.hashCanonical] calculates — so a file formatted with `kuml fmt --canonical`
     * has a byte-stable, reproducible model hash.
     *
     * Delegates to [ModelHasher.canonicalize] (single source of truth):
     * `canonical(s) == ModelHasher.canonicalize(s)` for all s.
     *
     * Idempotent: `canonical(canonical(s)) == canonical(s)`.
     */
    internal fun canonical(source: String): String = ModelHasher.canonicalize(source)

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
