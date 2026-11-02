@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind
import dev.kuml.renderer.theme.core.ElegantTheme
import dev.kuml.renderer.theme.core.KumlBrandTheme
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.renderer.theme.core.PlayfulTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe

/**
 * Test-only dark theme (not registered, not public). There is no built-in dark theme, but users bring their own,
 * so the dark palette must be checked against one.
 */
internal fun darkTestTheme(): KumlTheme {
    val base = PlainTheme()
    return base.copy(
        name = "dark-test",
        colors =
            base.colors.copy(
                background = KumlColor(0x1E1E1E),
                nodeFill = KumlColor(0x2A2A2A),
                foreground = KumlColor(0xE6E6E6),
                border = KumlColor(0x9AA4B2),
                muted = KumlColor(0x9A9A9A),
            ),
    )
}

class NoteTonePaletteTest :
    FunSpec(body = {
        val themes = listOf(PlainTheme(), KumlBrandTheme(), ElegantTheme(), PlayfulTheme(), darkTestTheme())

        test("contrast ratio follows WCAG") {
            NoteTones.contrastRatio(KumlColor.Black, KumlColor.White) shouldBeGreaterThanOrEqualTo 20.99
            NoteTones.contrastRatio(KumlColor.White, KumlColor.White) shouldBe 1.0
        }

        test("every tone has at least 3 to 1 against the note fill in every theme") {
            for (theme in themes) {
                for (tone in NoteTone.entries) {
                    val ratio = NoteTones.contrastRatio(NoteTones.colorOf(tone, theme), theme.colors.effectiveNodeFill)
                    withClue("${theme.name} / $tone") { ratio shouldBeGreaterThanOrEqualTo NoteTones.MIN_CONTRAST }
                }
            }
        }

        test("a colour below 3 to 1 falls back to the theme foreground") {
            val base = PlainTheme()
            val weak = base.copy(colors = base.colors.copy(muted = KumlColor(0xF0F0F0)))
            NoteTones.colorOf(NoteTone.NEUTRAL, weak) shouldBe weak.colors.foreground
        }

        test("every kind maps to a tone and no kind is without one") {
            for (k in NoteKind.entries.filter { it.hasIcon }) NoteIcons.tone(k) // total mapping, no exception
            NoteIcons.tone(NoteKind.WARNING) shouldBe NoteTone.WARNING
            NoteIcons.tone(NoteKind.ERROR) shouldBe NoteTone.DANGER
            NoteIcons.tone(NoteKind.DEPRECATED) shouldBe NoteTone.NEUTRAL
        }

        test("only the used tones get rules") {
            val css = NoteTones.css(setOf(NoteTone.INFO), PlainTheme())
            (".kuml-note-tone-info" in css) shouldBe true
            (".kuml-note-tone-danger" in css) shouldBe false
            NoteTones.css(emptySet(), PlainTheme()) shouldBe ""
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T = io.kotest.assertions.withClue(clue, block)
