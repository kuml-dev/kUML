package dev.kuml.io.latex

/**
 * Tuning knobs for [KumlLatexRenderer].
 *
 * Defaults produce a snippet-mode TikZ block that drops into an existing
 * LaTeX document via `\input{diagram.tex}`. Switch to [standalone] to get a
 * complete `.tex` file with `\documentclass{standalone}` and the necessary
 * preamble — handy for one-shot PDF builds via `pdflatex diagram.tex`.
 *
 * MVP scope (V2.0.2):
 *  - Class-diagram output only. Other UML diagram types fall back to a
 *    plain "node + label" representation without compartments.
 *  - One theme: the [`plain`][dev.kuml.renderer.theme.core.PlainTheme]-equivalent
 *    monochrome look, no Inter-via-`fontspec` magic yet (that lives in the
 *    `kuml` theme mapping → V2.x).
 *  - Snippet vs. standalone — both supported.
 *
 * The pixel→point scale is **important**. Layout coords are abstract pixels
 * (CSS-style), TikZ defaults to `cm`. We anchor on `pt` (1 px = 1 pt by
 * default) so a typical 600-px-wide layout renders to ~21 cm — a sensible
 * page width. Adjust [scale] to fit narrow columns or larger sheets.
 *
 * @property standalone If true, wrap the picture in a complete `.tex`
 *   document. Otherwise emit just the `\begin{tikzpicture}…\end{tikzpicture}`
 *   block — the snippet mode.
 * @property scale Multiplier applied to every coordinate. 1.0 = identity
 *   (1 layout px → 1 TikZ pt); 0.5 halves the picture, 2.0 doubles it.
 * @property indent Leading whitespace per logical TikZ statement —
 *   purely cosmetic, makes the produced source pleasant to diff.
 */
public data class LatexRenderOptions(
    val standalone: Boolean = false,
    val scale: Double = 1.0,
    val indent: String = "  ",
) {
    public companion object {
        /** Default options — snippet mode, identity scale. */
        public val DEFAULT: LatexRenderOptions = LatexRenderOptions()
    }
}
