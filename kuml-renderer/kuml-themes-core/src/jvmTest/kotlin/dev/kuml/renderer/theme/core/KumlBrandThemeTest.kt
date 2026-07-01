package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KumlBrandThemeTest :
    FunSpec({

        test("KumlBrandTheme name is 'kUML'") {
            KumlBrandTheme().name shouldBe "kUML"
        }

        test("KumlBrandTheme provides non-null instances for all theme slots") {
            val theme = KumlBrandTheme()

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

        test("KumlBrandTheme background is pure white — only elements carry brand colours") {
            val theme = KumlBrandTheme()
            theme.colors.background.toHex() shouldBe "#FFFFFF"
            theme.colors.foreground.toHex() shouldBe "#0D1525"
        }

        test("KumlBrandTheme nodes are subtly tinted off-white (distinct from white canvas)") {
            val theme = KumlBrandTheme()
            theme.colors.nodeFill?.toHex() shouldBe "#F8F5F0"
            theme.colors.effectiveNodeFill.toHex() shouldBe "#F8F5F0"
        }

        test("KumlBrandTheme border and edge are Navy") {
            val theme = KumlBrandTheme()
            theme.colors.border.toHex() shouldBe "#1D2B4F"
            theme.colors.edge.toHex() shouldBe "#1D2B4F"
        }

        test("KumlBrandTheme accent is Gold") {
            val theme = KumlBrandTheme()
            theme.colors.accent.toHex() shouldBe "#C49A2E"
        }

        test("KumlBrandTheme muted and edgeMuted are Slate-blue") {
            val theme = KumlBrandTheme()
            theme.colors.muted.toHex() shouldBe "#6B7A99"
            theme.colors.edgeMuted.toHex() shouldBe "#6B7A99"
        }

        test("KumlBrandTheme uses the Geist font stack with Inter fallback") {
            val theme = KumlBrandTheme()
            val expected = "Geist, Inter, system-ui, sans-serif"
            theme.typography.title.family shouldBe expected
            theme.typography.subtitle.family shouldBe expected
            theme.typography.body.family shouldBe expected
            theme.typography.small.family shouldBe expected
            theme.typography.stereotype.family shouldBe expected
        }

        test("KumlBrandTheme stereotype font is italic at 10pt") {
            val theme = KumlBrandTheme()
            theme.typography.stereotype.italic shouldBe true
            theme.typography.stereotype.sizePt shouldBe 10f
        }

        test("KumlBrandTheme uses softer rounded borders (corner radius 8px)") {
            val theme = KumlBrandTheme()
            theme.borders.cornerRadiusPx shouldBe 8f
            theme.borders.thickPx shouldBe 2.5f
        }
    })
