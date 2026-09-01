package dev.kuml.jetbrains.markdown

import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence

/**
 * EP-independent helpers for recognizing and parsing ```` ```kuml ```` Markdown code fences.
 *
 * Extracted out of the former `KumlMarkdownCodeFenceProvider` (a
 * `CodeFenceGeneratingProvider`, which is `@ApiStatus.Internal` and blocked the
 * JetBrains Marketplace verifier — see `KumlMarkdownPreviewExtension`, its
 * replacement). Kept as plain, dependency-free functions so both the preview
 * extension and [KumlMarkdownLineMarkerProvider] can share the exact same
 * recognition/parsing behavior without depending on the removed EP interface.
 */
internal object KumlMarkdownFenceInfo {
    private val ATTR_PAIR = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*(\S+)""")

    /**
     * Matches the former `CodeFenceGeneratingProvider.isApplicable` logic exactly —
     * behavior unchanged, only the entry point moved.
     */
    fun isKumlFence(infoString: String): Boolean {
        val trimmed = infoString.trim()
        return trimmed.equals("kuml", ignoreCase = true) ||
            trimmed.startsWith("kuml ", ignoreCase = true) ||
            trimmed.startsWith("kuml\t", ignoreCase = true) ||
            trimmed.startsWith("kuml{", ignoreCase = true)
    }

    /**
     * Parses code fence attributes from the language info string.
     *
     * Examples:
     *  - `kuml` -> {}
     *  - `kuml {theme="plain" name="diagram1"}` -> {theme: "plain", name: "diagram1"}
     *  - `kuml theme=plain name=diagram1` -> {theme: "plain", name: "diagram1"}
     */
    fun parseAttributes(infoString: String): Map<String, String> {
        val trimmed = infoString.trim()
        val afterLang =
            if (trimmed.startsWith("kuml", ignoreCase = true)) {
                trimmed.substring(4).trim()
            } else {
                trimmed
            }
        if (afterLang.isEmpty()) return emptyMap()

        val content =
            if (afterLang.startsWith("{") && afterLang.endsWith("}")) {
                afterLang.substring(1, afterLang.length - 1).trim()
            } else {
                afterLang
            }

        val result = mutableMapOf<String, String>()
        ATTR_PAIR.findAll(content).forEach { match ->
            val key = match.groupValues[1].ifEmpty { match.groupValues[3] }
            val value = match.groupValues[2].ifEmpty { match.groupValues[4] }
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    /**
     * Extracts the code block text enclosed between the opening and closing fences.
     *
     * Wortgleich aus `KumlMarkdownLineMarkerProvider.extractFenceContent` übernommen,
     * beide Callsites (Gutter-Popup + Preview-Extension) teilen sich jetzt diese eine
     * Implementierung.
     */
    fun extractFenceContent(fence: MarkdownCodeFence): String {
        val lines = fence.text.lines()
        if (lines.size <= 2) return ""
        return lines.subList(1, lines.size - 1).joinToString("\n")
    }

    /**
     * Resolves the fence the browser meant to reference, given its DOM-side [ordinal]
     * guess and the fence [browserSource] text it sent along, against [fences] — the
     * true PSI-derived `(infoString, source)` pairs for every kuml fence in the
     * document, in document order. Returns `null` only when [fences] is empty or
     * neither the ordinal nor a content match resolves anything.
     *
     * Pulled out of `KumlMarkdownPreviewExtension.Provider.fenceLookupFor` as a plain
     * function over lists/strings — no PSI, no [com.intellij.openapi.application.ReadAction] —
     * specifically so this content-verified-fallback logic (previously entirely
     * untested, per the finding this closes) can be unit-tested directly.
     *
     * ## Why the ordinal alone isn't trusted
     *
     * The browser's `ordinal` comes from `kuml-markdown-preview.js`'s own DOM-side
     * class-name heuristic (see its `isKumlClassToken`/`KUML_SIBLING_LANG_SUFFIXES`
     * comment), reconstructed from IntelliJ's lossy, mangled `class` attribute rather
     * than the real info string [isKumlFence] has direct access to. The two sides are
     * kept in sync as far as a class-string heuristic can (see that comment for the
     * residual, documented ambiguity), but a mismatch — e.g. a brand-new
     * `"kuml-<word>"` sibling fence language introduced before the JS-side exclusion
     * list is updated — would otherwise silently shift every later ordinal, rendering
     * fence N's diagram under fence M's theme/name (the MAJOR finding this guards
     * against). So [ordinal] is only a first guess: if the fence at that position
     * doesn't have the SAME source as [browserSource], this searches for the fence
     * whose content actually matches instead of trusting the ordinal.
     */
    fun resolveFence(
        fences: List<Pair<String, String>>,
        ordinal: Int,
        browserSource: String,
    ): Pair<String, String>? {
        val positional = fences.getOrNull(ordinal)
        if (positional != null && positional.second == browserSource) {
            // Fast path: the browser's ordinal and the PSI's ordinal agree.
            return positional
        }

        // Mismatch — recover by content instead of trusting the ordinal.
        // Returns null when nothing matches. Deliberately NOT falling back to the
        // positional guess: this pair's `second` is the SOURCE the caller then renders,
        // so returning a non-matching positional pair renders a DIFFERENT fence's
        // diagram under this fence. That is reachable, not theoretical — the browser's
        // DOM lags the PSI by the preview's debounce interval, so mid-typing the
        // browser legitimately sends a source no PSI fence matches yet, and the
        // positional guess is then simply the wrong fence. Returning null makes the
        // caller render the browser's OWN source under the default info string: the
        // worst case degrades to "correct diagram, default theme/name for one pass",
        // never "someone else's diagram".
        return fences.firstOrNull { it.second == browserSource }
    }
}
