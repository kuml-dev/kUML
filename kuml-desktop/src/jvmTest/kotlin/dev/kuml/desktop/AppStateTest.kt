package dev.kuml.desktop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

class AppStateTest : FunSpec({

    test("AppState initializes with welcome script") {
        AppState().script shouldBe AppState.WELCOME_SCRIPT
    }

    test("welcome script is non-blank") {
        AppState.WELCOME_SCRIPT.shouldNotBeBlank()
    }

    test("default theme is plain") {
        AppState().theme shouldBe "plain"
    }

    test("default language is en") {
        AppState().language shouldBe "en"
    }

    test("default lastSvg is empty") {
        AppState().lastSvg shouldBe ""
    }

    test("default lastError is null") {
        AppState().lastError shouldBe null
    }

    test("script mutation is observable") {
        val s = AppState()
        s.script = "classDiagram { }"
        s.script shouldBe "classDiagram { }"
    }

    test("theme mutation is observable") {
        val s = AppState()
        s.theme = "dark"
        s.theme shouldBe "dark"
    }

    test("language mutation is observable") {
        val s = AppState()
        s.language = "en"
        s.language shouldBe "en"
    }

    test("lastError mutation is observable") {
        val s = AppState()
        s.lastError = "Script error"
        s.lastError shouldBe "Script error"
    }

    test("lastSvg mutation is observable") {
        val s = AppState()
        s.lastSvg = "<svg/>"
        s.lastSvg shouldBe "<svg/>"
    }
})
