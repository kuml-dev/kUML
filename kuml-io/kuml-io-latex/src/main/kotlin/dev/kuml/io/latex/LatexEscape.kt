package dev.kuml.io.latex

import dev.kuml.layout.NodeId

/**
 * LaTeX-escape a user-supplied label so it can be dropped into a `\node{…}`
 * without breaking the parser or producing surprising glyphs.
 *
 * Conservative — we escape every character that has a special meaning in
 * LaTeX text mode. Class names, attribute names, operations, and association
 * labels can plausibly contain any of these.
 *
 * All ten LaTeX special characters are handled: `\` → `\textbackslash{}`,
 * `{` → `\{`, `}` → `\}`, `$` → `\$`, `&` → `\&`, `#` → `\#`, `%` → `\%`,
 * `_` → `\_`, `~` → `\textasciitilde{}`, `^` → `\textasciicircum{}`.
 * Additionally `<`, `>`, `«`, `»` are mapped to their safe text-mode macros.
 * Backslash injection is fully neutralised — there is no residual LaTeX
 * injection risk for any single-character special sequence.
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
 * Sanitise a [NodeId] into a valid TikZ node-name.
 *
 * TikZ node names must consist of alphanumeric characters and `_` only (hyphens
 * are valid too, but we stay conservative). Layout [NodeId] values are model-
 * element IDs which are usually already safe, but we sanitise defensively to
 * guarantee a single, consistent mapping across all renderers.  A divergence
 * here — e.g. one renderer allowing `-` while another does not — would produce
 * inconsistent cross-renderer `\draw … (nodeA) …` references inside the same
 * TikZ picture.
 */
internal fun tikzId(id: NodeId): String = "n_" + id.value.replace(Regex("[^A-Za-z0-9_]"), "_")

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
