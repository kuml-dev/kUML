package dev.kuml.codegen.m2m

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RequireFilesTest :
    FunSpec({
        test("a list of generated files passes") {
            val files = listOf(GeneratedFile(relativePath = "a.txt", content = "x"))
            GeneratedFileWriter.requireFiles(output = files, transformerId = "t") shouldBe files
        }

        test("a non-list output yields a clear error instead of a ClassCastException") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    GeneratedFileWriter.requireFiles(
                        output = "diagram",
                        transformerId = "uml-to-exposed-psm",
                    )
                }
            ex.message!! shouldContain "uml-to-exposed-psm"
        }
    })
