package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Headless unit tests for the theme-selection feature of [KumlPreviewPanel].
 *
 * All tests run without an IntelliJ application context — no PropertiesComponent,
 * no AllIcons, no real CLI. The panel degrades gracefully in headless mode.
 */
class KumlPreviewPanelThemeTest :
    FunSpec({

        // ── 1. Default theme ──────────────────────────────────────────────────

        test("KumlPreviewPanel: default theme is 'kuml'") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                panel.currentTheme shouldBe KumlPreviewPanel.DEFAULT_THEME
            } finally {
                panel.dispose()
            }
        }

        // ── 2. Custom initial theme ───────────────────────────────────────────

        test("KumlPreviewPanel: initialTheme param is respected") {
            val panel = KumlPreviewPanel(debounceMs = 50L, initialTheme = "elegant")
            try {
                panel.currentTheme shouldBe "elegant"
            } finally {
                panel.dispose()
            }
        }

        // ── 3. Invalid initial theme falls back to default ────────────────────

        test("KumlPreviewPanel: invalid initialTheme falls back to default") {
            val panel = KumlPreviewPanel(debounceMs = 50L, initialTheme = "not-a-theme")
            try {
                panel.currentTheme shouldBe KumlPreviewPanel.DEFAULT_THEME
            } finally {
                panel.dispose()
            }
        }

        // ── 4. THEMES list matches settings ───────────────────────────────────

        test("KumlPreviewPanel.THEMES contains same entries as KumlPreviewSettings.THEMES") {
            KumlPreviewPanel.THEMES shouldBe KumlPreviewSettings.THEMES
        }

        // ── 5. onThemeChanged callback is invoked ─────────────────────────────

        test("KumlPreviewPanel: onThemeChanged is null by default") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                panel.onThemeChanged shouldBe null
            } finally {
                panel.dispose()
            }
        }

        // ── 6. onThemeChanged can be set ──────────────────────────────────────

        test("KumlPreviewPanel: onThemeChanged callback can be wired") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                var received: String? = null
                panel.onThemeChanged = { received = it }
                panel.onThemeChanged shouldNotBe null
                // Manually simulate what the combobox ItemListener does.
                panel.currentTheme = "playful"
                panel.onThemeChanged!!("playful")
                received shouldBe "playful"
            } finally {
                panel.dispose()
            }
        }

        // ── 7. exportContext is null by default ───────────────────────────────

        test("KumlPreviewPanel: exportContext is null by default") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                panel.exportContext shouldBe null
            } finally {
                panel.dispose()
            }
        }

        // ── 8. Panel still instantiates with all four themes ──────────────────

        test("KumlPreviewPanel: instantiates with each valid theme without crashing") {
            KumlPreviewPanel.THEMES.forEach { theme ->
                val panel = KumlPreviewPanel(debounceMs = 50L, initialTheme = theme)
                try {
                    panel.currentTheme shouldBe theme
                } finally {
                    panel.dispose()
                }
            }
        }
    })
