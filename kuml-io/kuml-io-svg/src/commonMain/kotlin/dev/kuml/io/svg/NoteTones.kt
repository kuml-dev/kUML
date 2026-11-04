package dev.kuml.io.svg

import dev.kuml.layout.bridge.note.NoteSymbols
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlTheme
import kotlin.math.pow

/** Colour tone of a note kind (shared with the LaTeX renderer). */
internal typealias NoteTone = dev.kuml.layout.bridge.note.NoteTone

/** CSS class suffix (`kuml-note-tone-<suffix>`). */
internal val NoteTone.css: String get() = key

/**
 * Tone colours of the note symbols and the CSS rules for them.
 *
 * The rules are written only for tones a diagram really uses, so a diagram without kinded notes keeps its
 * style block byte for byte. Colours come in a light and a dark palette (chosen by the luminance of the node
 * fill); a colour with less than [MIN_CONTRAST]:1 against the node fill is replaced by the theme foreground.
 * Only commonMain APIs are used (no `String.format`).
 */
internal object NoteTones {
    /** Minimum contrast of a symbol against the note background (WCAG non-text contrast). */
    const val MIN_CONTRAST: Double = 3.0

    /** Width of the accent stripe on the left edge of a dog-ear note (px). */
    const val ACCENT_WIDTH: Float = 3f

    private fun isDark(c: KumlColor): Boolean {
        val r = (c.rgb shr 16) and 0xFF
        val g = (c.rgb shr 8) and 0xFF
        val b = c.rgb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140.0
    }

    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of [c]. */
    fun luminance(c: KumlColor): Double =
        0.2126 * channel((c.rgb shr 16) and 0xFF) + 0.7152 * channel((c.rgb shr 8) and 0xFF) + 0.0722 * channel(c.rgb and 0xFF)

    /** WCAG contrast ratio of two colours (1.0 to 21.0). */
    fun contrastRatio(
        a: KumlColor,
        b: KumlColor,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Colour of [tone] in [theme], guaranteed at least [MIN_CONTRAST]:1 against the note fill (else the theme foreground). */
    fun colorOf(
        tone: NoteTone,
        theme: KumlTheme,
    ): KumlColor {
        val c = theme.colors
        val fill = c.effectiveNodeFill
        val wanted =
            if (tone == NoteTone.NEUTRAL) {
                c.muted
            } else {
                KumlColor((if (isDark(fill)) NoteSymbols.DARK_RGB else NoteSymbols.LIGHT_RGB).getValue(tone))
            }
        return if (contrastRatio(a = wanted, b = fill) >= MIN_CONTRAST) wanted else c.foreground
    }

    /** CSS rules for the [used] tones; empty if none is used. */
    fun css(
        used: Set<NoteTone>,
        theme: KumlTheme,
    ): String =
        buildString {
            for (tone in NoteTone.entries) {
                if (tone !in used) continue
                val hex = colorOf(tone = tone, theme = theme).toHex()
                append(".kuml-note-tone-${tone.css} { fill: $hex; }\n")
                append(".kuml-note-accent-${tone.css} { fill: $hex; stroke: none; }\n")
                append(".kuml-note-bracket-${tone.css} { stroke: $hex; }\n")
            }
        }

    /** Tones whose symbol class appears in [svgFragment]. */
    fun usedIn(svgFragment: String): Set<NoteTone> =
        NoteTone.entries.filterTo(LinkedHashSet()) {
            "kuml-note-tone-${it.css}\"" in
                svgFragment
        }
}
