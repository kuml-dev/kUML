package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class ElegantThemeTest :
    FunSpec({

        test("ElegantTheme name is 'Elegant'") {
            ElegantTheme().name shouldBe "Elegant"
        }

        test("ElegantTheme provides non-null instances for all theme slots") {
            val theme = ElegantTheme()
            theme.colors shouldNotBe null
            theme.colors.background shouldNotBe null
            theme.colors.foreground shouldNotBe null
            theme.colors.border shouldNotBe null
            theme.colors.muted shouldNotBe null
            theme.colors.accent shouldNotBe null
            theme.colors.edge shouldNotBe null
            theme.colors.edgeMuted shouldNotBe null
            theme.typography shouldNotBe null
            theme.borders shouldNotBe null
        }

        test("ElegantTheme uses parchment cream background and espresso-black foreground") {
            val theme = ElegantTheme()
            theme.colors.background.toHex() shouldBe "#F4EDDD"
            theme.colors.foreground.toHex() shouldBe "#1C1814"
        }

        test("ElegantTheme accent is deep bordeaux") {
            ElegantTheme().colors.accent.toHex() shouldBe "#7A1F2D"
        }

        test("ElegantTheme uses a classical serif font stack with Garamond/Cormorant") {
            val theme = ElegantTheme()
            theme.typography.title.family shouldContain "Garamond"
            theme.typography.body.family shouldContain "serif"
            theme.typography.stereotype.family shouldContain "Garamond"
        }

        test("ElegantTheme stereotype font is italic") {
            ElegantTheme().typography.stereotype.italic shouldBe true
        }

        test("ElegantTheme borders are sharp-edged with very fine strokes — editorial print look") {
            val borders = ElegantTheme().borders
            borders.cornerRadiusPx shouldBe 0f
            borders.thinPx shouldBe 0.5f
            borders.regularPx shouldBe 0.75f
            borders.thickPx shouldBe 1.25f
        }

        test("ElegantTheme uses mahogany ink border (distinct from kuml's navy)") {
            ElegantTheme().colors.border.toHex() shouldBe "#4A2F22"
        }

        test("ElegantTheme title is slightly larger than body — editorial hierarchy") {
            val typo = ElegantTheme().typography
            typo.title.sizePt shouldBe 15f
            typo.body.sizePt shouldBe 11f
        }

        test("ElegantThemeProvider exposes the theme under key 'elegant'") {
            val provider = ElegantThemeProvider()
            provider.name shouldBe "elegant"
            provider.theme().name shouldBe "Elegant"
        }
    })
