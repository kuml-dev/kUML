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
 * Escape the text of a note (body, span text, list marker) for use inside a `\node{…}`.
 *
 * Control characters (including line breaks and tabs), the C1 range and the Unicode line/paragraph
 * separators become a space, so a note can never end a TeX line comment or start a new paragraph with
 * user-controlled content. The ellipsis and the bullet get their text macros; everything else goes through
 * [escapeLatex], which neutralises all ten LaTeX special characters. No hyperlink macro is ever produced.
 */
internal fun escapeNoteText(raw: String): String {
    val sb = StringBuilder(raw.length + 8)
    for (c in raw) {
        when {
            Character.isISOControl(c) || c == '\u2028' || c == '\u2029' -> sb.append(' ')
            c == '\u2026' -> sb.append("\\ldots{}")
            c == '\u2022' -> sb.append("\\textbullet{}")
            else -> sb.append(escapeLatex(c.toString()))
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

/**
 * Format a `Double` in TikZ cm units (the Service Blueprint renderer): at most two decimals, no trailing `.00`,
 * dot separator regardless of the JVM locale. Non-finite values become `0`.
 */
internal fun fmtCm(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    val r = Math.round(v * 100.0) / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, r)
}

/** Replaces control characters (including line breaks) and the Unicode line and paragraph separators by a space. */
internal fun stripControl(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (c in raw) sb.append(if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') ' ' else c)
    return sb.toString()
}

/**
 * Escape [raw] for a TeX line comment (`% …`). A line break inside a comment would end it and the rest of the
 * text would run as TeX code, so control characters become a space ([stripControl]) before [escapeLatex].
 */
internal fun commentSafe(raw: String): String = escapeLatex(stripControl(raw))
