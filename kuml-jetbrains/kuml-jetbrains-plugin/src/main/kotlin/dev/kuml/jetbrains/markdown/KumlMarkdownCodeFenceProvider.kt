package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import dev.kuml.jetbrains.preview.KumlPreviewHtml
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
        val outcome = KumlDocPreviewCache.getOrRender(cleanSource, theme, name)

        return when (outcome) {
            is KumlPreviewRenderer.Outcome.Svg -> {
                val sanitizedSvg = sanitizeSvg(outcome.svg)
                KumlPreviewHtml.buildSvgContainer(sanitizedSvg, name, theme, width)
            }
            is KumlPreviewRenderer.Outcome.Failure -> {
                KumlPreviewHtml.buildErrorContainer(outcome.message, name)
            }
            is KumlPreviewRenderer.Outcome.Empty -> {
                KumlPreviewHtml.buildEmptyContainer(name)
            }
        }
    }

    companion object {
        private val ATTR_PAIR = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*(\S+)""")

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
        fun sanitizeSvg(svg: String): String = KumlPreviewHtml.sanitizeSvg(svg)

        fun escapeHtml(text: String): String = KumlPreviewHtml.escapeHtml(text)

        fun escapeHtmlAttribute(text: String): String = KumlPreviewHtml.escapeHtmlAttribute(text)
    }
}
