package dev.kuml.jetbrains.preview

/**
 * Shared HTML builders and SVG sanitization used by Markdown and AsciiDoc previews.
 *
 * Both document formats emit the same `kuml-diagram-*` CSS classes so the rendered
 * containers look identical regardless of the host document language.
 */
internal object KumlPreviewHtml {
    private val SCRIPT_TAG_REGEX =
        Regex("""<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>""", RegexOption.IGNORE_CASE)
    private val EVENT_HANDLER_REGEX =
        Regex("""\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

    /**
     * Defense-in-depth SVG sanitization preventing execution of script tags or inline event handlers.
     */
    fun sanitizeSvg(svg: String): String {
        var sanitized = SCRIPT_TAG_REGEX.replace(svg, "")
        sanitized = EVENT_HANDLER_REGEX.replace(sanitized, "")
        return sanitized
    }

    fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun escapeHtmlAttribute(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun buildSvgContainer(
        svg: String,
        name: String,
        theme: String,
        width: String?,
    ): String {
        val widthStyle =
            if (width != null) {
                val formatted = if (width.all { it.isDigit() }) "${width}px" else width
                "max-width: $formatted; width: 100%;"
            } else {
                "max-width: 100%;"
            }

        val escapedName = escapeHtmlAttribute(name)
        val escapedTheme = escapeHtmlAttribute(theme)

        return """
<div class="kuml-diagram-container" data-kuml-name="$escapedName" data-kuml-theme="$escapedTheme" style="text-align: center; margin: 1.5em 0; overflow-x: auto; $widthStyle">
$svg
</div>
            """.trimIndent()
    }

    fun buildErrorContainer(
        errorMessage: String,
        name: String,
    ): String {
        val escapedMessage = escapeHtml(errorMessage)
        val escapedName = escapeHtml(name)
        return """
<div class="kuml-diagram-error" data-kuml-name="$escapedName" style="border: 1px solid #e06c75; background: rgba(224, 108, 117, 0.08); padding: 12px 16px; border-radius: 6px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 13px; color: #e06c75; margin: 1.5em 0; line-height: 1.4;">
    <div style="font-weight: 600; margin-bottom: 6px; display: flex; align-items: center; gap: 6px;">
        <span>kUML Diagram Error ($escapedName)</span>
    </div>
    <pre style="margin: 0; padding: 8px; background: rgba(0, 0, 0, 0.05); border-radius: 4px; overflow-x: auto; font-family: 'JetBrains Mono', monospace; font-size: 12px; white-space: pre-wrap;">$escapedMessage</pre>
</div>
            """.trimIndent()
    }

    fun buildEmptyContainer(name: String): String {
        val escapedName = escapeHtml(name)
        return """
<div class="kuml-diagram-empty" data-kuml-name="$escapedName" style="padding: 12px; border: 1px dashed #abb2bf; border-radius: 6px; text-align: center; color: #5c6370; font-style: italic; margin: 1.5em 0;">
    (Empty kUML diagram)
</div>
            """.trimIndent()
    }
}
