package dev.kuml.jetbrains.folding

/**
 * Pure-Kotlin helper for extracting code-folding candidates from `.kuml.kts` scripts.
 *
 * This object has **no IntelliJ Platform dependency** so it can be exercised in
 * plain JUnit/Kotest unit tests without an IDE sandbox.  [KumlFoldingBuilder]
 * delegates to this object for the actual scan work.
 *
 * ## Algorithm
 *
 * For each name in [DEFAULT_NAMES] a regex of the form
 * `\b<name>\s*(?:\([^)]*\))?\s*\{` is used to locate the opening `{` of the
 * lambda.  A bracket-counter then walks forward to find the matching closing `}`,
 * correctly handling arbitrary nesting.  Both offsets are stored in the returned
 * [FoldCandidate] so that [KumlFoldingBuilder] can create [com.intellij.lang.folding.FoldingDescriptor]
 * instances without any further text analysis.
 */
object KumlFoldingExtractor {
    /** Offset-based fold region — no IntelliJ types, fully testable. */
    data class FoldCandidate(
        /** The DSL function name, e.g. `"classOf"`. */
        val callName: String,
        /**
         * Short placeholder shown when the region is collapsed,
         * e.g. `classOf("User") {…}` or `umlModel {…}`.
         */
        val placeholder: String,
        /** Offset of the opening `{` (inclusive). */
        val lambdaStartOffset: Int,
        /** Offset of the matching closing `}` (inclusive). */
        val lambdaEndOffset: Int,
    )

    /** All DSL names that produce foldable lambda blocks. */
    val DEFAULT_NAMES: Set<String> =
        setOf(
            "umlModel",
            "classOf",
            "interfaceOf",
            "enumOf",
            "componentOf",
            "stateMachine",
            "c4Model",
            "sysml2Model",
            "diagram",
            "actDiagram",
            "stmDiagram",
            "bdd",
            "ibd",
            "uc",
            "req",
            "seq",
            "par",
            "partDef",
            "stateDef",
            "actionDef",
            "attributeDef",
            "portDef",
            "connectionDef",
            "enumDef",
            "requirementDef",
        )

    /**
     * Scans [text] and returns one [FoldCandidate] per DSL lambda block found.
     *
     * Candidates are returned in source order (ascending [FoldCandidate.lambdaStartOffset]).
     * Comments are not filtered out — this mirrors the approach used by
     * [dev.kuml.jetbrains.KumlElementExtractor] and keeps the implementation simple.
     *
     * @param text            Full text of a `.kuml.kts` file.
     * @param foldableNames   Set of DSL function names to fold; defaults to [DEFAULT_NAMES].
     */
    fun candidates(
        text: String,
        foldableNames: Set<String> = DEFAULT_NAMES,
    ): List<FoldCandidate> {
        if (text.isBlank()) return emptyList()

        // Build one combined regex that matches any of the foldable call heads.
        // Pattern: \b<name>\s*(?:\([^)]*\))?\s*\{
        // The optional (?:\([^)]*\))? captures a single-line argument list.
        // We deliberately keep the arg capture non-greedy / single-line to avoid
        // consuming multi-line expressions — but for DSL usage this is fine.
        val namesPattern =
            foldableNames
                .sortedByDescending { it.length } // longest first to avoid partial matches
                .joinToString("|") { Regex.escape(it) }

        val headRegex = Regex("""(?<!\w)($namesPattern)\s*(\([^)]*\))?\s*\{""")

        val result = mutableListOf<FoldCandidate>()

        for (match in headRegex.findAll(text)) {
            val callName = match.groupValues[1]
            val argList = match.groupValues[2] // may be empty string if no parens

            // The `{` is the last character of the match.
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(text, openBrace) ?: continue

            val placeholder = buildPlaceholder(callName, argList)
            result += FoldCandidate(callName, placeholder, openBrace, closeBrace)
        }

        result.sortBy { it.lambdaStartOffset }
        return result
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the offset of the `}` that matches the `{` at [openOffset],
     * or `null` if the text ends before a match is found.
     */
    private fun findMatchingBrace(
        text: String,
        openOffset: Int,
    ): Int? {
        var depth = 0
        for (i in openOffset until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * Builds the placeholder string shown when the fold is collapsed.
     *
     * Examples:
     * - `classOf("User") {…}`
     * - `umlModel {…}`
     */
    private fun buildPlaceholder(
        callName: String,
        argList: String,
    ): String {
        val firstArg = extractFirstStringArg(argList)
        return if (firstArg != null) {
            "$callName(\"$firstArg\") {…}"
        } else if (argList.isNotBlank()) {
            "$callName($argList) {…}"
        } else {
            "$callName {…}"
        }
    }

    /**
     * Extracts the first string-literal argument from an argument list string
     * such as `("User")`, `(name = "Order")`, or `("MyDiagram", other)`.
     *
     * Returns the unquoted content of the first `"…"` found, or `null` if none.
     */
    internal fun extractFirstStringArg(callWithArgs: String): String? {
        val match = Regex(""""([^"]*)"[^"]*""").find(callWithArgs) ?: return null
        return match.groupValues[1].ifEmpty { null }
    }
}
