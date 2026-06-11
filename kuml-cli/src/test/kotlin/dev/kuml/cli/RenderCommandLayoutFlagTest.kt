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
                themeName = "plain",
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
                themeName = "plain",
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
                themeName = "plain",
                layoutEngineOverride = "elk",
            )

            val content = outputFile.toFile().readText()
            content.startsWith("<?xml") shouldBe true

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }
    })
