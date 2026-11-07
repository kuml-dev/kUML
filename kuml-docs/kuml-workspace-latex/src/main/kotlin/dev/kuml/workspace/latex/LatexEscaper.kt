package dev.kuml.workspace.latex

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Turns untrusted text into LaTeX that pdflatex (T1, inputenc utf8, textcomp, amssymb) typesets as plain text.
 *
 * The contract: whatever the input, the output contains no control sequence other than the fixed ones listed
 * here, braces stay balanced, and no character can end a comment, open a group or start a command. Characters
 * that pdflatex cannot typeset become `\kumlglyph{U+XXXX}` so a compile never stops on "Unicode character not
 * set up for use with LaTeX".
 */
internal object LatexEscaper {
    /** The control sequences this escaper can produce; the injection test checks `main.tex` against this set. */
    val EMITTED_COMMANDS: Set<String> =
        setOf(
            "textbackslash",
            "textasciitilde",
            "textasciicircum",
            "textquotedbl",
            "textless",
            "textgreater",
            "textbar",
            "textasciigrave",
            "textquotesingle",
            "textdegree",
            "textpm",
            "texttimes",
            "textdiv",
            "textcopyright",
            "textregistered",
            "textmu",
            "textsuperscript",
            "textonequarter",
            "textonehalf",
            "textthreequarters",
            "guillemotleft",
            "guillemotright",
            "S",
            "P",
            "textexclamdown",
            "textquestiondown",
            "textcent",
            "pounds",
            "textyen",
            "textquoteleft",
            "textquoteright",
            "quotesinglbase",
            "textquotedblleft",
            "textquotedblright",
            "quotedblbase",
            "ldots",
            "textbullet",
            "textdagger",
            "textdaggerdbl",
            "texteuro",
            "texttrademark",
            "textrightarrow",
            "textleftarrow",
            "leftrightarrow",
            "Rightarrow",
            "Leftarrow",
            "Leftrightarrow",
            "leq",
            "geq",
            "neq",
            "approx",
            "infty",
            "checkmark",
            "kumlglyph",
            "textperthousand",
            "textsection",
            "allowbreak",
        )

    private const val MAX_UNKNOWN_KEPT = 20

    /** Collects the code points that had to be replaced by placeholders. */
    class Unknown {
        val codePoints = sortedSetOf<Int>()

        fun add(cp: Int) {
            if (codePoints.size < MAX_UNKNOWN_KEPT) codePoints += cp
        }
    }

    /** Drops control and invisible characters, normalises to NFC. Line breaks and tabs become a space. */
    fun sanitize(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || cp == 0x2028 || cp == 0x2029 -> sb.append(' ')
                Character.isISOControl(cp) -> Unit
                cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x2069 -> Unit
                cp == 0xFEFF || cp == 0x00AD || cp == 0x061C -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** Running text. */
    fun text(
        raw: String,
        unknown: Unknown? = null,
    ): String = escape(raw = raw, code = false, unknown = unknown)

    /** Monospaced text; spaces stay visible and breakable, hyphens do not ligate. */
    fun code(
        raw: String,
        unknown: Unknown? = null,
    ): String = escape(raw = raw, code = true, unknown = unknown)

    private fun escape(
        raw: String,
        code: Boolean,
        unknown: Unknown?,
    ): String {
        val clean = sanitize(raw)
        val sb = StringBuilder(clean.length + 16)
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) ascii(c = cp.toChar(), code = code, sb = sb) else unicode(cp = cp, sb = sb, unknown = unknown)
        }
        return sb.toString()
    }

    private fun ascii(
        c: Char,
        code: Boolean,
        sb: StringBuilder,
    ) {
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
            '"' -> sb.append("\\textquotedbl{}")
            '<' -> sb.append("\\textless{}")
            '>' -> sb.append("\\textgreater{}")
            '|' -> sb.append("\\textbar{}")
            '[' -> sb.append("{[}")
            ']' -> sb.append("{]}")
            '`' -> sb.append("\\textasciigrave{}")
            '\'' -> sb.append(if (code) "\\textquotesingle{}" else "'")
            ' ' -> sb.append(if (code) "\\ " else " ")
            '-' -> sb.append(if (code) "-{}" else "-")
            else -> sb.append(c)
        }
    }

    private val MAPPED: Map<Int, String> =
        mapOf(
            0x00A0 to "~",
            0x00A1 to "\\textexclamdown{}",
            0x00A2 to "\\textcent{}",
            0x00A3 to "\\pounds{}",
            0x00A5 to "\\textyen{}",
            0x00A7 to "\\S{}",
            0x00A9 to "\\textcopyright{}",
            0x00AB to "\\guillemotleft{}",
            0x00AE to "\\textregistered{}",
            0x00B0 to "\\textdegree{}",
            0x00B1 to "\\textpm{}",
            0x00B2 to "\\textsuperscript{2}",
            0x00B3 to "\\textsuperscript{3}",
            0x00B5 to "\\textmu{}",
            0x00B6 to "\\P{}",
            0x00B7 to "\\textbullet{}",
            0x00B9 to "\\textsuperscript{1}",
            0x00BB to "\\guillemotright{}",
            0x00BC to "\\textonequarter{}",
            0x00BD to "\\textonehalf{}",
            0x00BE to "\\textthreequarters{}",
            0x00BF to "\\textquestiondown{}",
            0x00D7 to "\\texttimes{}",
            0x00F7 to "\\textdiv{}",
            0x2002 to " ",
            0x2003 to " ",
            0x2009 to "\\,",
            0x202F to "\\,",
            0x2010 to "-",
            0x2011 to "-",
            0x2012 to "--",
            0x2013 to "--",
            0x2014 to "---",
            0x2015 to "---",
            0x2018 to "\\textquoteleft{}",
            0x2019 to "\\textquoteright{}",
            0x201A to "\\quotesinglbase{}",
            0x201C to "\\textquotedblleft{}",
            0x201D to "\\textquotedblright{}",
            0x201E to "\\quotedblbase{}",
            0x2020 to "\\textdagger{}",
            0x2021 to "\\textdaggerdbl{}",
            0x2022 to "\\textbullet{}",
            0x2026 to "\\ldots{}",
            0x2030 to "\\textperthousand{}",
            0x20AC to "\\texteuro{}",
            0x2122 to "\\texttrademark{}",
            0x2190 to "\\textleftarrow{}",
            0x2192 to "\\textrightarrow{}",
            0x2194 to "$\\leftrightarrow$",
            0x21D0 to "$\\Leftarrow$",
            0x21D2 to "$\\Rightarrow$",
            0x21D4 to "$\\Leftrightarrow$",
            0x2212 to "$-$",
            0x221E to "$\\infty$",
            0x2248 to "$\\approx$",
            0x2260 to "$\\neq$",
            0x2264 to "$\\leq$",
            0x2265 to "$\\geq$",
            0x2713 to "$\\checkmark$",
        )

    /** Latin Extended-A code points that T1/utf8 inputenc does not define (checked against TeX Live 2026). */
    private val UNDEFINED_LATIN_EXT_A: Set<Int> =
        setOf(0x0126, 0x0127, 0x0138, 0x013F, 0x0140, 0x0149, 0x0166, 0x0167, 0x017F)

    private fun unicode(
        cp: Int,
        sb: StringBuilder,
        unknown: Unknown?,
    ) {
        val mapped = MAPPED[cp]
        when {
            mapped != null -> sb.append(mapped)
            // Letters of Latin-1 and Latin Extended-A: inputenc (utf8) + T1 can typeset them.
            (cp in 0x00C0..0x00FF || cp in 0x0100..0x017F) && cp !in UNDEFINED_LATIN_EXT_A -> sb.appendCodePoint(cp)
            else -> {
                unknown?.add(cp)
                sb.append("\\kumlglyph{U+").append(String.format(Locale.ROOT, "%04X", cp)).append('}')
            }
        }
    }

    /** Like [code] but allows a line break after `/`, for URLs in footnotes. */
    fun breakableCode(
        raw: String,
        unknown: Unknown? = null,
    ): String = code(raw = raw, unknown = unknown).replace("/", "/\\allowbreak{}")

    /** Text that is safe inside a TeX `%` comment: ASCII letters, digits and a few separators only. */
    fun commentSafe(raw: String): String =
        sanitize(raw).map { if (it.code < 0x80 && (it.isLetterOrDigit() || it in " ._,:()/-")) it else '?' }.joinToString("")

    private val SCHEME = Regex("^(https?|mailto):", RegexOption.IGNORE_CASE)
    private const val URL_SAFE = "-._~:/?@!'()*+,;=&"

    /**
     * A URL for `\href`/`\texttt`, or `null` if it must not become a link. Only `http`, `https` and `mailto`
     * qualify (no `run:`, `file:`, `launch:`, `javascript:`); everything unusual is percent-encoded, `#` and `%`
     * are escaped for TeX, so no `\`, `{` or `}` can survive.
     */
    fun url(raw: String): String? {
        val trimmed = raw.trim()
        if (!SCHEME.containsMatchIn(trimmed)) return null
        val bytes = trimmed.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size + 16)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val c = b.toChar()
            when {
                b < 0x80 && (c.isLetterOrDigit() || c in URL_SAFE) -> sb.append(c)
                c == '#' -> sb.append("\\#")
                c == '%' && isHexPair(bytes = bytes, at = i + 1) -> sb.append("\\%")
                else -> {
                    sb.append("\\%").append(String.format(Locale.ROOT, "%02X", b))
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun isHexPair(
        bytes: ByteArray,
        at: Int,
    ): Boolean = at + 1 < bytes.size && bytes[at].toInt().toChar().isHexDigit() && bytes[at + 1].toInt().toChar().isHexDigit()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
