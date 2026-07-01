package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PlainThemeTest :
    FunSpec({

        test("PlainTheme name is Plain") {
            PlainTheme().name shouldBe "Plain"
        }

        test("PlainTheme provides non-null instances for all theme slots") {
            val theme = PlainTheme()

            theme.colors shouldNotBe null
            theme.colors.background shouldNotBe null
            theme.colors.foreground shouldNotBe null
            theme.colors.border shouldNotBe null
            theme.colors.muted shouldNotBe null
            theme.colors.accent shouldNotBe null
            theme.colors.edge shouldNotBe null
            theme.colors.edgeMuted shouldNotBe null

            theme.typography shouldNotBe null
            theme.typography.title shouldNotBe null
            theme.typography.subtitle shouldNotBe null
            theme.typography.body shouldNotBe null
            theme.typography.small shouldNotBe null
            theme.typography.stereotype shouldNotBe null

            theme.borders shouldNotBe null
        }

        test("PlainTheme background is white and foreground is black") {
            val theme = PlainTheme()
            theme.colors.background shouldBe KumlColor.White
            theme.colors.foreground shouldBe KumlColor.Black
        }
    })
