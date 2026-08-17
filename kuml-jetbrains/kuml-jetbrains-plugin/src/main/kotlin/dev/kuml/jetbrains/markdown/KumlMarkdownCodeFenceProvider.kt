package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import org.intellij.markdown.ast.ASTNode
import org.intellij.plugins.markdown.extensions.CodeFenceGeneratingProvider

/**
 * Code fence generating provider for ```` ```kuml ```` code blocks in Markdown files.
 *
 * Renders kUML diagram DSL blocks into inline SVG HTML elements for the
 * IntelliJ Markdown preview (JCEF panel).
 */
class KumlMarkdownCodeFenceProvider : CodeFenceGeneratingProvider {
    override fun isApplicable(language: String): Boolean {
        val trimmed = language.trim()
        return trimmed.equals("kuml", ignoreCase = true) ||
            trimmed.startsWith("kuml ", ignoreCase = true) ||
            trimmed.startsWith("kuml\t", ignoreCase = true) ||
            trimmed.startsWith("kuml{", ignoreCase = true)
    }

    override fun generateHtml(
        language: String,
        raw: String,
        node: ASTNode,
    ): String {
        val attributes = parseAttributes(language)
        val theme = attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()
        val name = attributes["name"] ?: "markdown-diagram"
        val width = attributes["width"]

        val cleanSource = raw.trimEnd('\n', '\r')
        val outcome = KumlMarkdownPreviewCache.getOrRender(cleanSource, theme, name)

        return when (outcome) {
            is KumlPreviewRenderer.Outcome.Svg -> {
                val sanitizedSvg = sanitizeSvg(outcome.svg)
                buildSvgContainer(sanitizedSvg, name, theme, width)
            }
            is KumlPreviewRenderer.Outcome.Failure -> {
                buildErrorContainer(outcome.message, name)
            }
            is KumlPreviewRenderer.Outcome.Empty -> {
                buildEmptyContainer(name)
            }
        }
    }

    companion object {
        private val ATTR_PAIR = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*(\S+)""")
        private val SCRIPT_TAG_REGEX = Regex("""<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>""", RegexOption.IGNORE_CASE)
        private val EVENT_HANDLER_REGEX = Regex("""\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

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
         * Defense-in-depth SVG sanitization preventing execution of script tags or inline event handlers.
         */
        fun sanitizeSvg(svg: String): String {
            var sanitized = SCRIPT_TAG_REGEX.replace(svg, "")
            sanitized = EVENT_HANDLER_REGEX.replace(sanitized, "")
            return sanitized
        }

        private fun buildSvgContainer(
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

        private fun buildErrorContainer(
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

        private fun buildEmptyContainer(name: String): String {
            val escapedName = escapeHtml(name)
            return """
<div class="kuml-diagram-empty" data-kuml-name="$escapedName" style="padding: 12px; border: 1px dashed #abb2bf; border-radius: 6px; text-align: center; color: #5c6370; font-style: italic; margin: 1.5em 0;">
    (Empty kUML diagram)
</div>
                """.trimIndent()
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
    }
}
