@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg

import dev.kuml.layout.bridge.note.NoteSymbols
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class NoteToneTokensTest :
    FunSpec(body = {
        val all = NoteTone.entries.toSet()

        fun withTokens(
            theme: KumlTheme,
            rgb: Map<NoteTone, Int>,
        ): KumlTheme {
            fun t(tone: NoteTone) = rgb[tone]?.let { KumlColor(it) }
            return theme.copy(
                colors =
                    theme.colors.copy(
                        noteInfo = t(NoteTone.INFO),
                        noteHint = t(NoteTone.HINT),
                        noteSuccess = t(NoteTone.SUCCESS),
                        noteWarning = t(NoteTone.WARNING),
                        noteDanger = t(NoteTone.DANGER),
                        noteImportant = t(NoteTone.IMPORTANT),
                    ),
            )
        }

        test("tokens set to the built-in defaults render byte-identical to unset tokens (light and dark)") {
            for ((theme, palette) in listOf(PlainTheme() to NoteSymbols.LIGHT_RGB, darkTestTheme() to NoteSymbols.DARK_RGB)) {
                val explicit = withTokens(theme, palette)
                NoteTones.css(all, explicit) shouldBe NoteTones.css(all, theme)
            }
        }

        test("an override reaches every kind of its tone and leaves the other tones untouched") {
            val base = PlainTheme()
            val themed = withTokens(base, mapOf(NoteTone.WARNING to 0x0055AA))
            val css = NoteTones.css(all, themed)
            css shouldContain ".kuml-note-tone-warning { fill: #0055AA; }"
            css shouldContain ".kuml-note-bracket-warning { stroke: #0055AA; }"
            val rest = { s: String -> s.lines().filterNot { it.contains("-warning ") }.joinToString("\n") }
            rest(css) shouldBe rest(NoteTones.css(all, base))
        }

        test("an override applies in a dark theme regardless of the dark palette") {
            val themed = withTokens(darkTestTheme(), mapOf(NoteTone.INFO to 0x88CCFF))
            NoteTones.contrastRatio(KumlColor(0x88CCFF), themed.colors.effectiveNodeFill) shouldBe
                NoteTones.contrastRatio(KumlColor(0x88CCFF), themed.colors.effectiveNodeFill)
            NoteTones.colorOf(NoteTone.INFO, themed) shouldBe KumlColor(0x88CCFF)
            NoteTones.css(setOf(NoteTone.INFO), themed) shouldContain "fill: #88CCFF;"
        }

        test("an override below 3 to 1 contrast falls back to the theme foreground") {
            val base = PlainTheme()
            val weak = KumlColor(0xFFEE88)
            NoteTones.contrastRatio(weak, base.colors.effectiveNodeFill) shouldBeLessThan NoteTones.MIN_CONTRAST
            val themed = withTokens(base, mapOf(NoteTone.WARNING to 0xFFEE88))
            NoteTones.colorOf(NoteTone.WARNING, themed) shouldBe themed.colors.foreground
        }
    })
