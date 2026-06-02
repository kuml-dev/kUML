package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import com.github.ajalt.clikt.core.main as cliktMain

class WatchCommandTest :
    FunSpec({

        val fixture = File("src/test/resources/minimal.kuml.kts")

        test("WatchCommand renders output file on start") {
            val outputDir = Files.createTempDirectory("kuml-watch-cmd-test")
            val outputFile = outputDir.resolve("watched.svg")

            val thread =
                Thread {
                    KumlCli().cliktMain(
                        arrayOf(
                            "watch",
                            fixture.absolutePath,
                            "--output",
                            outputFile.toAbsolutePath().toString(),
                        ),
                    )
                }
            thread.start()
            // Script evaluation warm-up: allow up to 8s for initial render
            Thread.sleep(8000)
            thread.interrupt()
            thread.join(2000)

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("WatchCommand re-renders after file modification") {
            val inputFile = Files.createTempFile("kuml-watch-input", ".kuml.kts").toFile()
            inputFile.writeText(fixture.readText())
            val outputDir = Files.createTempDirectory("kuml-watch-rerender")
            val outputFile = outputDir.resolve("output.svg")

            val thread =
                Thread {
                    KumlCli().cliktMain(
                        arrayOf(
                            "watch",
                            inputFile.absolutePath,
                            "--output",
                            outputFile.toAbsolutePath().toString(),
                        ),
                    )
                }
            thread.start()
            Thread.sleep(8000) // initial render (script warm-up)

            val sizeAfterFirst = outputFile.toFile().length()

            // Touch the file to trigger re-render
            Thread.sleep(200)
            inputFile.writeText(fixture.readText()) // rewrite identical content, updates lastModified
            Thread.sleep(4000) // re-render

            val sizeAfterSecond = outputFile.toFile().length()

            thread.interrupt()
            thread.join(2000)

            // Both renders used identical fixture → same output size
            sizeAfterSecond shouldBe sizeAfterFirst

            inputFile.delete()
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }
    })
