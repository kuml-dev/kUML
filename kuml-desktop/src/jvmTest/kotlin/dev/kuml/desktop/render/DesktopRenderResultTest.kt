package dev.kuml.desktop.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DesktopRenderResultTest : FunSpec({
    test("Svg result holds svg string") {
        DesktopRenderResult.Svg("<svg/>").svg shouldBe "<svg/>"
    }
    test("Error result holds message") {
        DesktopRenderResult.Error("err").message shouldBe "err"
    }
    test("Svg is DesktopRenderResult") {
        DesktopRenderResult.Svg("<svg/>").shouldBeInstanceOf<DesktopRenderResult>()
    }
    test("Error is DesktopRenderResult") {
        DesktopRenderResult.Error("x").shouldBeInstanceOf<DesktopRenderResult>()
    }
})
