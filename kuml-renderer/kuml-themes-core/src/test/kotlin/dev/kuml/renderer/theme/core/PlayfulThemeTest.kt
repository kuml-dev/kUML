package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PlayfulThemeTest :
    FunSpec({

        test("PlayfulTheme name is 'Playful'") {
            PlayfulTheme().name shouldBe "Playful"
        }

        test("PlayfulTheme provides non-null instances for all theme slots") {
            val theme = PlayfulTheme()
            theme.colors shouldNotBe null
            theme.typography shouldNotBe null
            theme.borders shouldNotBe null
        }

        test("PlayfulTheme uses creamy background and indigo foreground") {
            val theme = PlayfulTheme()
            theme.colors.background.toHex() shouldBe "#FFFDF7"
            theme.colors.foreground.toHex() shouldBe "#1A1240"
        }

        test("PlayfulTheme accent is coral") {
            PlayfulTheme().colors.accent.toHex() shouldBe "#FF7A45"
        }

        test("PlayfulTheme border is royal violet and edge is teal") {
            val theme = PlayfulTheme()
            theme.colors.border.toHex() shouldBe "#5E3FBE"
            theme.colors.edge.toHex() shouldBe "#2DB39A"
        }

        test("PlayfulTheme uses a rounded sans-serif font stack") {
            val expected = "Nunito, Quicksand, Comic Neue, system-ui, sans-serif"
            val theme = PlayfulTheme()
            theme.typography.title.family shouldBe expected
            theme.typography.body.family shouldBe expected
        }

        test("PlayfulTheme uses larger default font sizes than Plain") {
            val playful = PlayfulTheme().typography
            val plain = PlainTheme().typography
            (playful.title.sizePt > plain.title.sizePt) shouldBe true
            (playful.body.sizePt > plain.body.sizePt) shouldBe true
        }

        test("PlayfulTheme has bold rounded borders (cornerRadius 12px, thick strokes)") {
            val borders = PlayfulTheme().borders
            borders.cornerRadiusPx shouldBe 12f
            borders.thickPx shouldBe 3f
        }

        test("PlayfulThemeProvider exposes the theme under key 'playful'") {
            val provider = PlayfulThemeProvider()
            provider.name shouldBe "playful"
            provider.theme().name shouldBe "Playful"
        }
    })
