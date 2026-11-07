package dev.kuml.cli.export

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class LatexOutputGuardTest :
    FunSpec({
        fun out(): File = File(Files.createTempDirectory("kuml-guard").toFile(), "out")

        test("text and binary files are written byte for byte, parent directories are created") {
            val dir = out()
            val bytes = byteArrayOf(0, 1, 2, -1, -128, 127)
            LatexOutputGuard.writeAllBytes(
                outDir = dir,
                files = linkedMapOf("images/a.png" to bytes, "main.tex" to "ä".toByteArray()),
                overwrite = false,
            )
            File(dir, "images/a.png").readBytes().toList() shouldBe bytes.toList()
            File(dir, "main.tex").readText() shouldBe "ä"
            dir.walkTopDown().none { it.name.endsWith(".tmp") } shouldBe true
        }

        test("the String overload still writes UTF-8") {
            val dir = out()
            LatexOutputGuard.writeAll(outDir = dir, files = mapOf("a.tex" to "ü"), overwrite = false)
            File(dir, "a.tex").readBytes().toList() shouldBe "ü".toByteArray().toList()
        }

        test("an existing file without overwrite is refused before anything is written") {
            val dir = out()
            dir.mkdirs()
            File(dir, "main.tex").writeText("old")
            val e =
                shouldThrow<LatexFolderExportException> {
                    LatexOutputGuard.writeAllBytes(
                        outDir = dir,
                        files =
                            linkedMapOf(
                                "images/a.png" to byteArrayOf(1),
                                "main.tex" to byteArrayOf(2),
                            ),
                        overwrite = false,
                    )
                }
            e.code shouldBe LatexFolderExportException.Code.OUTPUT_EXISTS
            File(dir, "images").exists() shouldBe false
            File(dir, "main.tex").readText() shouldBe "old"
        }

        test("a symbolic link as images directory is refused and nothing is written") {
            val dir = out()
            dir.mkdirs()
            val elsewhere = Files.createTempDirectory("kuml-guard-target").toFile()
            val linked = runCatching { Files.createSymbolicLink(File(dir, "images").toPath(), elsewhere.toPath()) }.isSuccess
            if (linked) {
                val e =
                    shouldThrow<LatexFolderExportException> {
                        LatexOutputGuard.writeAllBytes(
                            outDir = dir,
                            files =
                                linkedMapOf(
                                    "main.tex" to byteArrayOf(1),
                                    "images/a.png" to byteArrayOf(2),
                                ),
                            overwrite = true,
                        )
                    }
                e.code shouldBe LatexFolderExportException.Code.PATH_OUTSIDE_OUTPUT
                File(dir, "main.tex").exists() shouldBe false
                elsewhere.list()!!.isEmpty() shouldBe true
            }
        }

        test("a path that leaves the output directory is refused") {
            val dir = out()
            shouldThrow<LatexFolderExportException> {
                LatexOutputGuard.writeAllBytes(outDir = dir, files = mapOf("../x.tex" to byteArrayOf(1)), overwrite = false)
            }.code shouldBe LatexFolderExportException.Code.PATH_OUTSIDE_OUTPUT
        }
    })
