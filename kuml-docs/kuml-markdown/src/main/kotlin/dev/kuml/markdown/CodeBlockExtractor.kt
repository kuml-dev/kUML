package dev.kuml.markdown

/**
 * Finds ```` ```kuml ```` fenced code blocks in a Markdown document.
 *
 * Recognised forms (after the opening fence):
 *   ```` ```kuml ````                          — plain
 *   ```` ```kuml {name="hello" width=800} ```` — with attribute map (CommonMark/Pandoc style)
 *   ```` ```kuml name="hello" width=800 ````   — with bare attributes
 *
 * Closing fence is the next line beginning with three (or more) backticks.
 * The extractor never executes scripts; it only slices the source.
 */
public object CodeBlockExtractor {
    private val OPEN_FENCE = Regex("^\\s*(```+)kuml(?:\\s+(.*))?$")
    private val CLOSE_FENCE = Regex("^\\s*```+\\s*$")
    private val ATTR_PAIR = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"|(\\w+)\\s*=\\s*(\\S+)")

    /** Extracts all kuml code blocks, preserving source order. */
    public fun extract(markdown: String): List<KumlCodeBlock> {
        val lines = markdown.split('\n')
        val result = mutableListOf<KumlCodeBlock>()
        var i = 0
        while (i < lines.size) {
            val match = OPEN_FENCE.matchEntire(lines[i])
            if (match == null) {
                i++
                continue
            }
            val infoString =
                match.groupValues
                    .getOrNull(2)
                    ?.trim()
                    .orEmpty()
            val attributes = parseAttributes(infoString)
            val startLine = i + 1 // 1-based
            // Scan to closing fence
            var j = i + 1
            val buffer = StringBuilder()
            while (j < lines.size && CLOSE_FENCE.matchEntire(lines[j]) == null) {
                buffer.append(lines[j])
                if (j < lines.size - 1) buffer.append('\n')
                j++
            }
            // Trim trailing newline if any
            val source = buffer.toString().trimEnd('\n')
            val endLine = (j + 1).coerceAtMost(lines.size)
            result.add(
                KumlCodeBlock(
                    source = source,
                    startLine = startLine,
                    endLine = endLine,
                    attributes = attributes,
                ),
            )
            i = j + 1
        }
        return result
    }

    private fun parseAttributes(infoString: String): Map<String, String> {
        if (infoString.isEmpty()) return emptyMap()
        // Strip optional surrounding {…}
        val content =
            infoString.trim().let {
                if (it.startsWith("{") && it.endsWith("}")) {
                    it.substring(1, it.length - 1).trim()
                } else {
                    it
                }
            }
        val map = mutableMapOf<String, String>()
        ATTR_PAIR.findAll(content).forEach { m ->
            val key = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }
}
