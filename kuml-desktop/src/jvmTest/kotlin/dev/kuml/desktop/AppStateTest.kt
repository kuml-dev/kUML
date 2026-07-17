package dev.kuml.desktop

import dev.kuml.desktop.io.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.io.File
import java.nio.file.Files

class AppStateTest :
    FunSpec({

        test("AppState initializes with welcome script") {
            AppState().script shouldBe AppState.WELCOME_SCRIPT
        }

        test("welcome script is non-blank") {
            AppState.WELCOME_SCRIPT.shouldNotBeBlank()
        }

        test("default theme is kuml") {
            AppState().theme shouldBe "kuml"
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

        // --- V3.0.12 new tests ---

        test("no-arg AppState() has theme=kuml via DEFAULT") {
            AppState().theme shouldBe "kuml"
        }

        test("AppState(initialSettings) adopts theme, language and recentFiles from settings") {
            val settings =
                AppSettings(
                    theme = "dark",
                    language = "de",
                    recentFiles = listOf("/tmp/previous.kuml.kts"),
                    lastDir = "/tmp",
                )
            val state = AppState(settings)
            state.theme shouldBe "dark"
            state.language shouldBe "de"
            state.recentFiles shouldContain "/tmp/previous.kuml.kts"
            state.lastDir shouldBe "/tmp"
        }

        test("loadFrom() sets script, currentFile, isDirty=false and adds to recentFiles") {
            val tempDir = Files.createTempDirectory("kuml-appstate-test").toFile()
            val file = File(tempDir, "test.kuml.kts").also { it.writeText("classDiagram {}") }
            try {
                val state = AppState()
                state.isDirty = true
                state.loadFrom(file, "classDiagram {}")
                state.script shouldBe "classDiagram {}"
                state.currentFile shouldBe file
                state.isDirty shouldBe false
                state.recentFiles shouldContain file.absolutePath
            } finally {
                tempDir.deleteRecursively()
            }
        }

        test("markSaved() sets currentFile and isDirty=false") {
            val tempDir = Files.createTempDirectory("kuml-appstate-test").toFile()
            val file = File(tempDir, "saved.kuml.kts").also { it.writeText("") }
            try {
                val state = AppState()
                state.isDirty = true
                state.markSaved(file)
                state.currentFile shouldBe file
                state.isDirty shouldBe false
                state.recentFiles shouldContain file.absolutePath
            } finally {
                tempDir.deleteRecursively()
            }
        }

        test("toSettings() serializes current state correctly") {
            val settings = AppSettings(theme = "blueprint", language = "de", windowWidth = 1400, windowHeight = 900)
            val state = AppState(settings)
            val result = state.toSettings()
            result.theme shouldBe "blueprint"
            result.language shouldBe "de"
            result.windowWidth shouldBe 1400
            result.windowHeight shouldBe 900
        }
    })
