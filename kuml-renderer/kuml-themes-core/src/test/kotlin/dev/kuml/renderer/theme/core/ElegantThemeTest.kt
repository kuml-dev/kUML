package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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

        test("ElegantTheme uses warm-off-white background and dark warm-grey foreground") {
            val theme = ElegantTheme()
            theme.colors.background.toHex() shouldBe "#FAFAF7"
            theme.colors.foreground.toHex() shouldBe "#2A2520"
        }

        test("ElegantTheme accent is dusty rose") {
            ElegantTheme().colors.accent.toHex() shouldBe "#A0524D"
        }

        test("ElegantTheme uses a serif font stack") {
            val expected = "EB Garamond, Garamond, Cambria, Georgia, serif"
            val theme = ElegantTheme()
            theme.typography.title.family shouldBe expected
            theme.typography.body.family shouldBe expected
            theme.typography.stereotype.family shouldBe expected
        }

        test("ElegantTheme stereotype font is italic") {
            ElegantTheme().typography.stereotype.italic shouldBe true
        }

        test("ElegantTheme borders are subtle (low cornerRadius, thin strokes)") {
            val borders = ElegantTheme().borders
            borders.cornerRadiusPx shouldBe 2f
            borders.thinPx shouldBe 0.75f
            borders.thickPx shouldBe 1.5f
        }

        test("ElegantThemeProvider exposes the theme under key 'elegant'") {
            val provider = ElegantThemeProvider()
            provider.name shouldBe "elegant"
            provider.theme().name shouldBe "Elegant"
        }
    })
