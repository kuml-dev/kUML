package dev.kuml.cli

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

/**
 * Verifiziert die `--layout`-Flag-Logik des Render-Pipelines.
 *
 * Zwei Varianten:
 * 1. Unit-Tests der Engine-Auswahl via [LayoutEngineRegistry] + [RenderPipeline]
 *    internen Hilfsfunktionen (schnell, kein Script-Compile).
 * 2. Integrations-Smoke-Tests mit `minimal.kuml.kts` — nur die wichtigsten Pfade.
 */
class RenderCommandLayoutFlagTest :
    FunSpec({

        afterEach {
            LayoutEngineRegistry.clear()
        }

        // ── Engine-Registry-Logik: direkte Tests ────────────────────────────

        test("Registry: elk registered first → pickFor(UmlClass, null) returns elk.layered (production default)") {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, null)
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("elk.layered")
        }

        test("Registry: explicit grid preference for UmlClass → returns kuml.grid") {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            val engine = LayoutEngineRegistry.pickFor(DiagramKind.UmlClass, LayoutEngineId("kuml.grid"))
            engine shouldNotBe null
            engine!!.id shouldBe LayoutEngineId("kuml.grid")
        }

        // ── RenderPipeline integration (with script compile) ─────────────────

        test("kuml render minimal.kuml.kts (no --layout flag) → SVG produced with elk engine (default)") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-layout-flag-test")
            val outputFile = outputDir.resolve("out.svg")

            // Default: no layout override → auto → elk for all diagram types
            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                layoutEngineOverride = null,
            )

            val content = outputFile.toFile().readText()
            content.startsWith("<?xml") shouldBe true

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("kuml render minimal.kuml.kts --layout=grid → SVG produced with grid engine (opt-in)") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-layout-flag-grid-test")
            val outputFile = outputDir.resolve("out.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                layoutEngineOverride = "grid",
            )

            val content = outputFile.toFile().readText()
            content.startsWith("<?xml") shouldBe true

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("kuml render minimal.kuml.kts --layout=elk → SVG produced with elk engine") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-layout-flag-elk-test")
            val outputFile = outputDir.resolve("out.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                layoutEngineOverride = "elk",
            )

            val content = outputFile.toFile().readText()
            content.startsWith("<?xml") shouldBe true

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        // ── ERM `--layout` override (previously silently ignored) ────────────

        test("kuml render ERM valid-ecommerce.kuml.kts --layout=grid → grid engine used, not ELK") {
            val fixture = File("src/test/resources/erm/valid-ecommerce.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-layout-flag-erm-grid-test")
            val elkOutput = outputDir.resolve("elk.svg")
            val gridOutput = outputDir.resolve("grid.svg")

            RenderPipeline.run(
                input = fixture,
                output = elkOutput,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                layoutEngineOverride = null,
            )
            RenderPipeline.run(
                input = fixture,
                output = gridOutput,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                layoutEngineOverride = "grid",
            )

            val elkContent = elkOutput.toFile().readText()
            val gridContent = gridOutput.toFile().readText()
            elkContent.startsWith("<?xml") shouldBe true
            gridContent.startsWith("<?xml") shouldBe true
            // Before the fix, `renderErm` hard-coded `elk.layered` regardless of
            // `layoutEngineOverride`, so both runs produced byte-identical
            // node positioning. The grid engine lays entities out on a
            // deterministic grid instead of running ELK's layered algorithm,
            // so the two SVGs must diverge once the override is respected.
            elkContent shouldNotBe gridContent

            elkOutput.toFile().delete()
            gridOutput.toFile().delete()
            outputDir.toFile().delete()
        }

        test("kuml render ERM valid-ecommerce.kuml.kts --layout=bogus → error mentions unknown engine") {
            val fixture = File("src/test/resources/erm/valid-ecommerce.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-layout-flag-erm-bogus-test")
            val outputFile = outputDir.resolve("out.svg")

            val exception =
                runCatching {
                    RenderPipeline.run(
                        input = fixture,
                        output = outputFile,
                        format = "svg",
                        width = 1024,
                        themeName = "kuml",
                        layoutEngineOverride = "totally-bogus-engine",
                    )
                }.exceptionOrNull()

            exception shouldNotBe null
            (exception!!.message ?: "").contains("not found") shouldBe true

            outputDir.toFile().delete()
        }
    })
