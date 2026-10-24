package dev.kuml.codegen.m2m

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class GeneratedFileWriterTest :
    FunSpec({

        fun tempDir() = Files.createTempDirectory("kuml-writer-test").toFile().also { it.deleteOnExit() }

        test("writes files in sorted order including subdirectories") {
            val dir = tempDir()
            val written =
                GeneratedFileWriter.write(
                    files =
                        listOf(
                            GeneratedFile(relativePath = "b/B.kt", content = "b"),
                            GeneratedFile(relativePath = "a.kt", content = "a"),
                        ),
                    outputDir = dir,
                    rejectDuplicates = true,
                )
            written.map { it.relativeTo(dir).invariantSeparatorsPath } shouldBe listOf("a.kt", "b/B.kt")
            dir.resolve("b/B.kt").readText() shouldBe "b"
        }

        test("rejects traversal and absolute paths and writes nothing") {
            val dir = tempDir()
            val parent = dir.resolve("out")
            shouldThrow<IllegalArgumentException> {
                GeneratedFileWriter.write(
                    files =
                        listOf(
                            GeneratedFile(relativePath = "ok.kt", content = "x"),
                            GeneratedFile(relativePath = "../x", content = "y"),
                        ),
                    outputDir = parent,
                    rejectDuplicates = false,
                )
            }.message!! shouldContain "escapes"
            shouldThrow<IllegalArgumentException> {
                GeneratedFileWriter.write(
                    files = listOf(GeneratedFile(relativePath = "/etc/x", content = "y")),
                    outputDir = parent,
                    rejectDuplicates = false,
                )
            }
            shouldThrow<IllegalArgumentException> {
                GeneratedFileWriter.write(
                    files = listOf(GeneratedFile(relativePath = "a/../../x", content = "y")),
                    outputDir = parent,
                    rejectDuplicates = false,
                )
            }
            parent.exists() shouldBe false
            dir.resolve("x").exists() shouldBe false
        }

        test("duplicates are an error only when requested") {
            val dir = tempDir()
            val files = listOf(GeneratedFile(relativePath = "A.kt", content = "1"), GeneratedFile(relativePath = "A.kt", content = "2"))
            shouldThrow<IllegalArgumentException> {
                GeneratedFileWriter.write(files = files, outputDir = dir, rejectDuplicates = true)
            }.message!! shouldContain "A.kt"
            GeneratedFileWriter.write(files = files, outputDir = dir, rejectDuplicates = false)
            dir.resolve("A.kt").readText() shouldBe "2"
        }
    })
