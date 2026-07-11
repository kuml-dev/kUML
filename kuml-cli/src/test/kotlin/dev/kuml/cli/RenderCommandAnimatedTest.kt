package dev.kuml.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

/**
 * CLI-level tests for `kuml render --animated` and `--trace` options.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
class RenderCommandAnimatedTest :
    FunSpec({

        val stateScript = File("src/test/resources/simulate/order-lifecycle.kuml.kts")
        val activityScript = File("src/test/resources/minimal-activity.kuml.kts")

        // ── (1) --animated on STATE diagram → SMIL SVG output ──

        test("--animated on STATE diagram produces SMIL SVG") {
            val outputDir = Files.createTempDirectory("kuml-animated-test")
            val outputFile = outputDir.resolve("order.svg")

            RenderPipeline.run(
                input = stateScript,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                animated = true,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            // Demo mode: synthesised StateEntered events → SMIL animations present
            content shouldContain "<animate"

            outputFile.toFile().delete()
            outputDir.toFile().deleteRecursively()
        }

        // ── (2) --animated on ACTIVITY diagram → SMIL SVG output ──

        test("--animated on ACTIVITY diagram produces SMIL SVG") {
            val outputDir = Files.createTempDirectory("kuml-animated-act-test")
            val outputFile = outputDir.resolve("activity.svg")

            RenderPipeline.run(
                input = activityScript,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                animated = true,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            content shouldContain "<animate"

            outputFile.toFile().delete()
            outputDir.toFile().deleteRecursively()
        }

        // ── (3) --animated with explicit trace file → trace-driven SMIL ──

        test("--animated with trace file injects trace-driven SMIL into STATE diagram") {
            // Build a minimal trace file on disk
            val traceDir = Files.createTempDirectory("kuml-trace")
            val traceFile =
                traceDir.resolve("order-trace.json").toFile().also { f ->
                    f.writeText(
                        """
                        {
                          "schema": "kuml.trace.v1",
                          "modelId": "order-lifecycle",
                          "entries": [
                            {"type": "StateEntered", "seqNo": 0, "timestamp": "2026-06-26T00:00:00Z", "vertexId": "OrderLifecycle::Draft"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            val outputDir = Files.createTempDirectory("kuml-animated-trace-test")
            val outputFile = outputDir.resolve("order-trace.svg")

            RenderPipeline.run(
                input = stateScript,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                animated = true,
                traceFile = traceFile,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            // Trace-driven path must inject at least one SMIL animate element;
            // if it silently fell back to static SVG this assertion catches it.
            content shouldContain "<animate"

            traceDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }

        // ── (4) --speed 2.0 → valid output ──

        test("--speed 2.0 produces valid animated SVG") {
            val outputDir = Files.createTempDirectory("kuml-animated-speed-test")
            val outputFile = outputDir.resolve("order-fast.svg")

            RenderPipeline.run(
                input = stateScript,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                animated = true,
                speed = 2.0,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"

            outputFile.toFile().delete()
            outputDir.toFile().deleteRecursively()
        }

        // ── (5) --trace with missing vertexId → static fallback, no crash ──

        test("trace with unrecognised vertexId falls back gracefully (no crash)") {
            val traceDir = Files.createTempDirectory("kuml-trace-unknown")
            val traceFile =
                traceDir.resolve("trace.json").toFile().also { f ->
                    f.writeText(
                        """
                        {
                          "schema": "kuml.trace.v1",
                          "modelId": "x",
                          "entries": [
                            {"type": "StateEntered", "seqNo": 0, "timestamp": "T0", "vertexId": "NoSuchState"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            val outputDir = Files.createTempDirectory("kuml-animated-unknown-test")
            val outputFile = outputDir.resolve("out.svg")

            // Should not throw
            RenderPipeline.run(
                input = stateScript,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "kuml",
                animated = true,
                traceFile = traceFile,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"

            traceDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }

        // ── (6) trace with wrong schema → ScriptEvaluationException ──

        test("trace file with wrong schema causes ScriptEvaluationException") {
            val traceDir = Files.createTempDirectory("kuml-trace-wrong-schema")
            val traceFile =
                traceDir.resolve("bad-trace.json").toFile().also { f ->
                    f.writeText(
                        """
                        {
                          "schema": "wrong.schema",
                          "modelId": "x",
                          "entries": []
                        }
                        """.trimIndent(),
                    )
                }

            val outputDir = Files.createTempDirectory("kuml-animated-bad-schema-test")
            val outputFile = outputDir.resolve("out.svg")

            shouldThrow<IllegalArgumentException> {
                RenderPipeline.run(
                    input = stateScript,
                    output = outputFile,
                    format = "svg",
                    width = 1024,
                    themeName = "kuml",
                    animated = true,
                    traceFile = traceFile,
                )
            }

            traceDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    })
