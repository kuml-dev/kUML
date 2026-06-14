package dev.kuml.cli.showcase

import dev.kuml.cli.RenderPipeline
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Exportiert alle Website-Beispiele (*.kuml.kts) als PNG-Dateien.
 *
 * Jedes Beispiel aus `kuml-examples/src/main/kotlin/dev/kuml/examples/`
 * durchläuft die vollständige Render-Pipeline (Script-Eval → ELK-Layout →
 * SVG-Renderer → Batik-Rasterisierung) und wird als `<name>.png` in
 * `kuml-cli/build/sample-output/examples/<subpfad>/` abgelegt.
 *
 * Tests dienen zwei Zwecken:
 *  1. **Regressions-Guard**: Jedes Beispiel muss fehlerfrei kompilieren
 *     und ein gültiges PNG (Magic-Bytes + Mindestgröße) erzeugen.
 *  2. **Visuelle Inspektion**: Die erzeugten PNGs liegen im Build-Verzeichnis
 *     und können nach `./gradlew :kuml-cli:test` manuell geöffnet werden.
 *
 * **Laufzeit**: Jedes `.kuml.kts` kompiliert über den Kotlin-Script-Host
 * (~4–5 s pro Script). Bei ~27 Beispielen ~2–3 Minuten gesamt — nur beim
 * ersten Lauf ohne warm cache. Das ist das erwartete Verhalten für
 * End-to-End-Render-Tests.
 *
 * **Erweiterung**: Neue Beispiele in `kuml-examples` werden automatisch
 * aufgenommen — kein manuelles Nachtragen nötig.
 */

private val PNG_MAGIC = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }

/** Minimale Dateigröße für ein sinnvolles PNG (kein leerer Canvas). */
private const val PNG_MIN_BYTES = 1_000

class WebsiteExamplesPngExportTest :
    FunSpec({

        // ── Verzeichnisse ─────────────────────────────────────────────────────
        // Pfad relativ zum Modul-Root (kuml-cli/), wie in RenderPipelineTest etabliert.
        val examplesRoot = File("../kuml-examples/src/main/kotlin/dev/kuml/examples")
        val outputRoot = File("build/sample-output/examples")

        // ── Beispiel-Scripts entdecken ────────────────────────────────────────
        // Ausgeschlossene Unterverzeichnisse: Scripts die keine Diagramme,
        // sondern Modell-Transformationen (M2M) oder andere Nicht-Render-Artefakte
        // produzieren. RenderPipeline.run() erwartet ein Diagram als letzten Ausdruck.
        val nonRenderDirs = setOf("c4-to-uml")

        val exampleScripts: List<File> =
            if (examplesRoot.isDirectory) {
                examplesRoot
                    .walkTopDown()
                    .filter { file ->
                        if (!file.isFile || !file.name.endsWith(".kuml.kts")) return@filter false
                        // Prüfen ob das Script in einem ausgeschlossenen Unterverzeichnis liegt
                        val rel = file.relativeTo(examplesRoot).invariantSeparatorsPath
                        val topDir = rel.substringBefore("/")
                        topDir !in nonRenderDirs
                    }.sortedBy { it.invariantSeparatorsPath }
                    .toList()
            } else {
                emptyList()
            }

        // ── Einen Test pro Script ─────────────────────────────────────────────
        exampleScripts.forEach { scriptFile ->
            // Relativer Pfad für Testname und Ausgabepfad, z. B. "c4/container.kuml.kts"
            val relPath = scriptFile.relativeTo(examplesRoot).invariantSeparatorsPath
            val pngRelPath = relPath.removeSuffix(".kuml.kts") + ".png"
            val outputFile = outputRoot.resolve(pngRelPath.replace('/', File.separatorChar)).toPath()

            test("website example '$relPath' renders to valid PNG") {
                Files.createDirectories(outputFile.parent)

                RenderPipeline.run(
                    input = scriptFile,
                    output = outputFile,
                    format = "png",
                    // 3600 px statt 1200 — realistische Schemata wie das
                    // PZB-Datenbank-Diagramm erreichen mit Connection-aware
                    // Sizing (V2.x) ~3500 px SVG-Breite. 3600 liefert nahezu
                    // 1:1-Auflösung, kleinere Beispiele werden nur leicht
                    // hochskaliert.
                    width = 3_600,
                    themeName = "plain",
                )

                val bytes = outputFile.toFile().readBytes()

                // PNG-Magic-Header: 89 50 4E 47 0D 0A 1A 0A
                bytes.take(8) shouldBe PNG_MAGIC
                // Batik muss etwas rasterisiert haben — kein leerer Puffer
                (bytes.size >= PNG_MIN_BYTES) shouldBe true
            }
        }

        // ── Mindestanzahl als Sanity-Check ────────────────────────────────────
        // Schlägt an, wenn examplesRoot nicht gefunden wird (falscher Arbeitsordner).
        test("examples directory contains at least 20 example scripts") {
            (exampleScripts.size >= 20) shouldBe true
        }
    })
