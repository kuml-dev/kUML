package dev.kuml.markdown

/**
 * A single ```` ```kuml ```` fenced code block extracted from a Markdown source.
 *
 * @property source The raw script source between the fences (no leading/trailing newline).
 * @property startLine 1-based line number of the opening fence in the original document.
 * @property endLine 1-based line number of the closing fence in the original document.
 * @property attributes Optional attribute map parsed from the info string,
 *  e.g. `name="hello" width=800` → `{name=hello, width=800}`.
 */
public data class KumlCodeBlock(
    val source: String,
    val startLine: Int,
    val endLine: Int,
    val attributes: Map<String, String> = emptyMap(),
) {
    /** Convenience: optional `name` attribute (used as default file stem for linked output). */
    public val name: String? get() = attributes["name"]

    /** Convenience: optional `width` attribute (used for linked PNG output). */
    public val width: Int? get() = attributes["width"]?.toIntOrNull()
}
