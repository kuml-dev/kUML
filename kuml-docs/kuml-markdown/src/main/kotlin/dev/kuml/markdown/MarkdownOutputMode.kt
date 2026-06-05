package dev.kuml.markdown

import java.io.File

/**
 * Selects how rendered diagrams are embedded back into the Markdown.
 *
 *  - [InlineSvg] — the ```` ```kuml ```` block is replaced by the raw `<svg …>` element.
 *    Best for static site generators that accept inline HTML/SVG.
 *  - [LinkedSvg] — the SVG is written to [assetsDir] and the block is replaced by an
 *    `![](relative/path.svg)` Markdown image link.
 *  - [LinkedPng] — same as [LinkedSvg] but renders PNG (via Batik) at the given width.
 */
public sealed class MarkdownOutputMode {
    /** Inline `<svg>` element (no external files). */
    public data object InlineSvg : MarkdownOutputMode()

    /** Write `.svg` files into [assetsDir] and reference them via `![](…)` links. */
    public data class LinkedSvg(
        val assetsDir: File,
    ) : MarkdownOutputMode()

    /** Write `.png` files into [assetsDir] (at [widthPx]) and reference them via `![](…)` links. */
    public data class LinkedPng(
        val assetsDir: File,
        val widthPx: Int = 1024,
    ) : MarkdownOutputMode()
}
