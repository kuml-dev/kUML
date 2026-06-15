package dev.kuml.layout

/**
 * Greedy word-wrap helper shared between size providers and SVG renderers.
 *
 * Size providers use [wrapToWidth] to predict how many lines a description
 * occupies and allocate box height accordingly. The renderers call the same
 * function to emit one `<tspan>` per line. As long as both sides pass the
 * same `(text, maxWidthPx, charPx)` triple, line counts stay in sync — that
 * sync is what keeps text from overflowing the box.
 *
 * The function lives in `kuml-layout-api` because it's the only module
 * reachable from both `kuml-layout-bridge` and `kuml-io-svg` without
 * introducing new dependencies.
 */
public object TextWrap {
    /**
     * Splits [text] into lines that each fit within [maxWidthPx], measured at
     * [charPx] pixels per character. Breaks on whitespace; a single word
     * exceeding [maxWidthPx] becomes its own (overflowing) line — callers
     * compensate by widening the container to fit it.
     *
     * Returns an empty list for blank input.
     */
    public fun wrapToWidth(
        text: String,
        maxWidthPx: Float,
        charPx: Float,
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val words = trimmed.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var currentLen = 0

        for (word in words) {
            val wordLen = word.length
            val needSpace = currentLen > 0
            val tentativeLen = currentLen + (if (needSpace) 1 else 0) + wordLen
            val tentativeWidth = tentativeLen * charPx

            if (current.isEmpty()) {
                current.append(word)
                currentLen = wordLen
            } else if (tentativeWidth <= maxWidthPx) {
                current.append(' ').append(word)
                currentLen = tentativeLen
            } else {
                lines.add(current.toString())
                current.clear()
                current.append(word)
                currentLen = wordLen
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
