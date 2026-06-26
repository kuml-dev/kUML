package dev.kuml.render.smil

/**
 * Internal XML-escaping utilities for SMIL element generation.
 *
 * Ensures that all attribute values and text content are properly XML-escaped
 * before embedding in SVG output, preventing injection vulnerabilities.
 */
internal object SmilXml {
    /**
     * Escape [s] for use as an XML attribute value or text node.
     *
     * Replaces: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`, `'` → `&apos;`.
     */
    fun escape(s: String): String =
        buildString(s.length + 8) {
            for (ch in s) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

    /**
     * Build an XML attribute string `name="escaped-value"`.
     */
    fun attr(
        name: String,
        value: String,
    ): String = "$name=\"${escape(value)}\""
}
