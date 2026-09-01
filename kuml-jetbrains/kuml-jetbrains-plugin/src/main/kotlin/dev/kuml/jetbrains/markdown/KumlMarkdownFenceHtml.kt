package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import dev.kuml.jetbrains.preview.KumlPreviewHtml

/**
 * Renders a single ```` ```kuml ```` Markdown code fence to its preview HTML.
 *
 * This is the former `KumlMarkdownCodeFenceProvider.generateHtml` body, extracted
 * into a plain, EP-independent function. The behavior is byte-identical to before:
 * same cache key derivation, same theme/name/width attribute handling, same three
 * HTML container shapes (SVG / error / empty). Kept dependency-free (no JCEF, no
 * IntelliJ preview-extension types) so it stays unit-testable exactly like before,
 * and so [KumlMarkdownPreviewExtension] can call it from a pooled background thread.
 */
internal object KumlMarkdownFenceHtml {
    /**
     * @param infoString the fence's full info string, e.g. `kuml {theme="plain" name="x" width=600}`
     * @param source the fence's body content
     */
    fun render(
        infoString: String,
        source: String,
    ): String {
        val attributes = KumlMarkdownFenceInfo.parseAttributes(infoString)
        val theme = attributes["theme"]?.takeIf { it in KumlPreviewSettings.THEMES } ?: KumlPreviewSettings.theme()
        val name = attributes["name"] ?: "markdown-diagram"
        val width = attributes["width"]

        val cleanSource = source.trimEnd('\n', '\r')
        val outcome = KumlDocPreviewCache.getOrRender(cleanSource, theme, name)

        return when (outcome) {
            is KumlPreviewRenderer.Outcome.Svg -> {
                val sanitizedSvg = KumlPreviewHtml.sanitizeSvg(outcome.svg)
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

    /**
     * Last-resort container for an *unexpected* failure (PSI lookup blew up, CLI process
     * could not be started at all, …) — as opposed to [KumlPreviewRenderer.Outcome.Failure],
     * which is a diagram that rendered and reported an error.
     *
     * Exists so [KumlMarkdownPreviewExtension.onRequest] can still answer every request:
     * the bridge script treats a fence with an unanswered in-flight request as done and
     * never retries it, so dropping the response would leave a permanently blank fence
     * with no indication that anything went wrong.
     */
    fun renderError(throwable: Throwable): String {
        val message = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
        return KumlPreviewHtml.buildErrorContainer(message, "markdown-diagram")
    }
}
