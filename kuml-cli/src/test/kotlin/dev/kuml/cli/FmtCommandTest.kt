package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class FmtCommandTest :
    FunSpec({

        test("FmtCommand formats file in place") {
            val tmpFile = Files.createTempFile("kuml-fmt-test", ".kuml.kts").toFile()
            tmpFile.writeText("classOf(\"A\")   \n\n\n\tattribute(\"id\", \"UUID\")  \n")

            KumlCli().test(listOf("fmt", tmpFile.absolutePath))

            tmpFile.readText() shouldBe "classOf(\"A\")\n\n    attribute(\"id\", \"UUID\")\n"
            tmpFile.delete()
        }

        test("FmtCommand --check exits with code 6 when file needs formatting") {
            val tmpFile = Files.createTempFile("kuml-fmt-check", ".kuml.kts").toFile()
            val originalContent = "classOf(\"A\")   \n"
            tmpFile.writeText(originalContent)

            val result = KumlCli().test(listOf("fmt", "--check", tmpFile.absolutePath))
            result.statusCode shouldBe ExitCodes.FMT_CHECK_FAILED

            // File must NOT be modified in check mode
            tmpFile.readText() shouldBe originalContent
            tmpFile.delete()
        }
    })
