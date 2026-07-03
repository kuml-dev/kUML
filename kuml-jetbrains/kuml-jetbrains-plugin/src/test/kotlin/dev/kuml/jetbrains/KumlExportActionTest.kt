package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Headless unit tests for export-related pure helpers.
 *
 * All tests run without an IntelliJ application context. Tests cover:
 * - [KumlExportFormat] enum mapping (cliFormat, extension, displayName)
 * - [KumlCliRenderer.buildRenderArgs] argument construction
 * - Filename derivation logic
 */
class KumlExportActionTest :
    FunSpec({

        // ── KumlExportFormat mappings ─────────────────────────────────────────

        test("KumlExportFormat.SVG: cliFormat=svg, extension=svg") {
            KumlExportFormat.SVG.cliFormat shouldBe "svg"
            KumlExportFormat.SVG.extension shouldBe "svg"
            KumlExportFormat.SVG.displayName shouldBe "SVG"
        }

        test("KumlExportFormat.PNG: cliFormat=png, extension=png") {
            KumlExportFormat.PNG.cliFormat shouldBe "png"
            KumlExportFormat.PNG.extension shouldBe "png"
            KumlExportFormat.PNG.displayName shouldBe "PNG"
        }

        test("KumlExportFormat.TEX: cliFormat=latex, extension=tex (not 'latex')") {
            KumlExportFormat.TEX.cliFormat shouldBe "latex"
            KumlExportFormat.TEX.extension shouldBe "tex"
            KumlExportFormat.TEX.displayName shouldBe "TeX (LaTeX)"
        }

        test("KumlExportFormat.fromExtensionOrNull: finds correct entry") {
            KumlExportFormat.fromExtensionOrNull("svg") shouldBe KumlExportFormat.SVG
            KumlExportFormat.fromExtensionOrNull("png") shouldBe KumlExportFormat.PNG
            KumlExportFormat.fromExtensionOrNull("tex") shouldBe KumlExportFormat.TEX
            KumlExportFormat.fromExtensionOrNull("unknown") shouldBe null
        }

        test("KumlExportFormat has exactly six entries") {
            KumlExportFormat.entries.size shouldBe 6
        }

        test("KumlExportFormat fromExtensionOrNull resolves animated formats") {
            KumlExportFormat.fromExtensionOrNull("apng") shouldBe KumlExportFormat.APNG
            KumlExportFormat.fromExtensionOrNull("webp") shouldBe KumlExportFormat.WEBP
            KumlExportFormat.fromExtensionOrNull("mp4") shouldBe KumlExportFormat.MP4
        }

        test("KumlExportFormat.MP4: cliFormat=mp4, extension=mp4") {
            KumlExportFormat.MP4.cliFormat shouldBe "mp4"
            KumlExportFormat.MP4.extension shouldBe "mp4"
            KumlExportFormat.MP4.displayName shouldBe "Animated MP4"
        }

        // ── KumlCliRenderer.buildRenderArgs ───────────────────────────────────

        test("buildRenderArgs: produces correct arg array for SVG") {
            val binary = File("/usr/local/bin/kuml")
            val script = File("/tmp/test.kuml.kts")
            val output = File("/tmp/test.svg")

            val args = KumlCliRenderer.buildRenderArgs(binary, script, output, "svg", "kuml")

            args shouldBe
                arrayOf(
                    "/usr/local/bin/kuml",
                    "render",
                    "/tmp/test.kuml.kts",
                    "-o",
                    "/tmp/test.svg",
                    "-f",
                    "svg",
                    "--theme",
                    "kuml",
                )
        }

        test("buildRenderArgs: passes latex as cliFormat for TeX") {
            val binary = File("/usr/local/bin/kuml")
            val script = File("/tmp/diagram.kuml.kts")
            val output = File("/tmp/diagram.tex")

            val args = KumlCliRenderer.buildRenderArgs(binary, script, output, "latex", "plain")

            // -f latex, NOT -f tex
            val fIndex = args.indexOf("-f")
            fIndex shouldNotBe -1
            args[fIndex + 1] shouldBe "latex"
        }

        test("buildRenderArgs: theme is passed via --theme flag") {
            val binary = File("/usr/local/bin/kuml")
            val script = File("/tmp/diagram.kuml.kts")
            val output = File("/tmp/diagram.png")

            val args = KumlCliRenderer.buildRenderArgs(binary, script, output, "png", "elegant")

            val themeIndex = args.indexOf("--theme")
            themeIndex shouldNotBe -1
            args[themeIndex + 1] shouldBe "elegant"
        }

        // ── Filename derivation helpers ───────────────────────────────────────

        test("filename derivation: strips .kuml.kts and appends format extension") {
            val sourceName = "my-diagram.kuml.kts"
            val baseName = sourceName.removeSuffix(".kuml.kts").removeSuffix(".kts")
            val defaultName = "$baseName.${KumlExportFormat.SVG.extension}"
            defaultName shouldBe "my-diagram.svg"
        }

        test("filename derivation: strips only .kts if .kuml suffix missing") {
            val sourceName = "diagram.kts"
            val baseName = sourceName.removeSuffix(".kuml.kts").removeSuffix(".kts")
            val defaultName = "$baseName.${KumlExportFormat.PNG.extension}"
            defaultName shouldBe "diagram.png"
        }
    })
