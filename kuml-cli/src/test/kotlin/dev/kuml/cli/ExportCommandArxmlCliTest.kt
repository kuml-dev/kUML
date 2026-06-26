package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * CLI tests for `kuml export --format arxml`.
 *
 * Uses reflection to verify that the ARXML exporter is loaded correctly from the classpath
 * (kuml-io-arxml is on the testRuntimeOnly classpath via the CLI module).
 *
 * V3.1.36 — initial implementation.
 */
class ExportCommandArxmlCliTest :
    FunSpec({
        val arxmlAvailable =
            try {
                Class.forName("dev.kuml.io.arxml.ArxmlClassicExporter")
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        /**
         * Writes a temporary component-diagram kuml.kts script whose root is a UmlPackage
         * (required by ArxmlClassicExporter). Returns the script file.
         */
        fun writeArxmlCompatibleScript(dir: File): File =
            dir.resolve("autosar-test.kuml.kts").apply {
                writeText(
                    """
                    componentDiagram("AUTOSAR Sensor Composition") {
                        val iSpeed = interfaceOf("ISpeed")
                        component("SpeedSensorSwc") {
                            provides(iSpeed)
                        }
                    }
                    """.trimIndent(),
                )
            }

        fun writeC4Script(dir: File): File =
            dir.resolve("c4-only.kuml.kts").apply {
                writeText(
                    """
                    import dev.kuml.c4.dsl.*
                    import dev.kuml.c4.model.*

                    c4Model("Test C4") {
                        val usr = person("User")
                        val sys = softwareSystem("System")
                        systemContextDiagram("Context") {
                            include(usr, sys)
                        }
                    }
                    """.trimIndent(),
                )
            }

        test("kuml export --format arxml writes an .arxml file") {
            if (!arxmlAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-arxml-export-").toFile()
            val script = writeArxmlCompatibleScript(tmpDir)
            val out = tmpDir.resolve("output.arxml")
            try {
                val result = KumlCli().test("export --format arxml ${script.absolutePath} -o ${out.absolutePath}")
                result.statusCode shouldBe 0
                out.exists() shouldBe true
                val content = out.readText()
                content shouldContain "<AUTOSAR"
                content shouldContain "SpeedSensorSwc"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml export --format arxml rejects C4 script with SCRIPT_ERROR") {
            if (!arxmlAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-arxml-c4-").toFile()
            val script = writeC4Script(tmpDir)
            try {
                val result = KumlCli().test("export --format arxml ${script.absolutePath}")
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml export --format arxml derives .arxml extension when -o omitted") {
            if (!arxmlAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-arxml-derive-").toFile()
            val script = writeArxmlCompatibleScript(tmpDir)
            try {
                val result = KumlCli().test("export --format arxml ${script.absolutePath}")
                result.statusCode shouldBe 0
                // The derived output file should be next to the script
                val derived = tmpDir.resolve("autosar-test.arxml")
                derived.exists() shouldBe true
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml export --format arxml shows FORMAT_NOT_AVAILABLE when arxml classes absent") {
            // This test only runs when the arxml module is NOT on the classpath.
            // Since in normal test execution it IS available (testRuntimeOnly), we skip.
            if (arxmlAvailable) return@test

            val tmpDir = Files.createTempDirectory("kuml-arxml-unavail-").toFile()
            val script = writeArxmlCompatibleScript(tmpDir)
            try {
                val result = KumlCli().test("export --format arxml ${script.absolutePath}")
                result.statusCode shouldBe ExitCodes.FORMAT_NOT_AVAILABLE
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    })
