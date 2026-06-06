package dev.kuml.io.latex

/**
 * LaTeX-escape a user-supplied label so it can be dropped into a `\node{…}`
 * without breaking the parser or producing surprising glyphs.
 *
 * Conservative — we escape every character that has a special meaning in
 * LaTeX text mode. Class names, attribute names, operations, and association
 * labels can plausibly contain any of these.
 *
 * Not a substitute for a full LaTeX sanitiser: a user-controlled label
 * containing arbitrary backslash sequences will still produce valid (but
 * possibly empty / weird) output. The MVP target is reasonable model
 * authors, not adversarial input.
 */
internal fun escapeLatex(raw: String): String {
    val sb = StringBuilder(raw.length + 8)
    for (c in raw) {
        when (c) {
            '\\' -> sb.append("\\textbackslash{}")
            '{' -> sb.append("\\{")
            '}' -> sb.append("\\}")
            '$' -> sb.append("\\$")
            '&' -> sb.append("\\&")
            '#' -> sb.append("\\#")
            '%' -> sb.append("\\%")
            '_' -> sb.append("\\_")
            '~' -> sb.append("\\textasciitilde{}")
            '^' -> sb.append("\\textasciicircum{}")
            '<' -> sb.append("\\textless{}")
            '>' -> sb.append("\\textgreater{}")
            // Guillemets — used everywhere for stereotypes. The standalone
            // pre-amble loads `T1` + a Unicode-aware font, but for snippet
            // mode the safe form is `\guillemotleft` / `\guillemotright`.
            '«' -> sb.append("\\guillemotleft{}")
            '»' -> sb.append("\\guillemotright{}")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Format a `Float` for TikZ coords — three decimals, dot separator, no exponent.
 *
 * We force `Locale.US` so a developer running tests in a `de_DE` JVM doesn't
 * accidentally produce `1,500pt` (which TikZ parses as two arguments and
 * complains).
 */
internal fun fmtCoord(v: Float): String {
    if (v.isNaN() || v.isInfinite()) return "0.000"
    return String.format(java.util.Locale.US, "%.3f", v)
}
