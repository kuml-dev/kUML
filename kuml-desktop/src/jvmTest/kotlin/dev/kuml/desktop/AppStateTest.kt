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
                state.loadFrom(file = file, content = "classDiagram {}")
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

        // --- P5 — Ansichtsmodus (Quelltext/Geteilt/Diagramm) ---

        test("default viewMode is SPLIT") {
            AppState().viewMode shouldBe AppState.ViewMode.SPLIT
        }

        test("viewMode mutation is observable") {
            val s = AppState()
            s.viewMode = AppState.ViewMode.DIAGRAM
            s.viewMode shouldBe AppState.ViewMode.DIAGRAM
        }

        test("AppState(initialSettings) adopts viewMode from settings") {
            val settings = AppSettings(viewMode = "DIAGRAM")
            AppState(settings).viewMode shouldBe AppState.ViewMode.DIAGRAM
        }

        // Regression guard: an old settings file (predating P5) or a future enum value read by
        // an older build must fall back to SPLIT instead of crashing composition on startup.
        test("AppState(initialSettings) with an unparsable viewMode falls back to SPLIT without crashing") {
            val settings = AppSettings(viewMode = "NONSENSE_FUTURE_VALUE")
            AppState(settings).viewMode shouldBe AppState.ViewMode.SPLIT
        }

        test("toSettings() mirrors viewMode for all three enum values") {
            AppState.ViewMode.entries.forEach { mode ->
                val state = AppState()
                state.viewMode = mode
                state.toSettings().viewMode shouldBe mode.name
            }
        }

        // --- V3.7.4 — showWatermark ---

        test("default showWatermark is false") {
            AppState().showWatermark shouldBe false
        }

        test("AppState(initialSettings) adopts showWatermark from settings") {
            AppState(AppSettings(showWatermark = true)).showWatermark shouldBe true
        }

        test("toSettings() mirrors showWatermark") {
            val state = AppState()
            state.showWatermark = true
            state.toSettings().showWatermark shouldBe true
        }
    })
