package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * CLI tests for `kuml export --format profile-uml`.
 *
 * Uses reflection to verify that ProfileXmiExporter is loaded correctly from the
 * classpath (kuml-io-emf is on the testRuntimeOnly classpath via the CLI module).
 *
 * V3.1.41 — initial implementation.
 */
class ExportCommandProfileUmlCliTest :
    FunSpec({
        val emfAvailable =
            try {
                Class.forName("dev.kuml.io.emf.ProfileXmiExporter")
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        /**
         * Writes a minimal profile script that returns a KumlProfile via the AUTOSAR profile.
         */
        fun writeProfileScript(dir: File): File =
            dir.resolve("my-profile.kuml.kts").apply {
                writeText(
                    """
                    import dev.kuml.profile.autosar.autosarProfile
                    autosarProfile
                    """.trimIndent(),
                )
            }

        /**
         * Writes a script that returns a non-Profile value (wrong type).
         */
        fun writeUmlScript(dir: File): File =
            dir.resolve("uml-only.kuml.kts").apply {
                writeText(
                    """
                    classDiagram("Test") {
                        umlClass("Foo")
                    }
                    """.trimIndent(),
                )
            }

        test("profile-uml format is accepted in the choice list (no 'Invalid value' error)") {
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val outputFile = tmpDir.resolve("output.profile.uml")
            val cmd = KumlCli()
            val result = cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            // Should not mention "Invalid value" (i.e. the choice is registered)
            result.output shouldNotContain "Invalid value"
        }

        test("export --format profile-uml writes a .profile.uml file when EMF is available") {
            if (!emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-export-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val outputFile = tmpDir.resolve("autosar.profile.uml")
            val cmd = KumlCli()
            val result = cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            result.statusCode shouldBe 0
            outputFile.exists() shouldBe true
        }

        test("export --format profile-uml output file contains uml:Profile XML element") {
            if (!emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-xml-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val outputFile = tmpDir.resolve("autosar.profile.uml")
            val cmd = KumlCli()
            cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            outputFile.readText() shouldContain "Profile"
        }

        test("export --format profile-uml prints success message") {
            if (!emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-msg-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val outputFile = tmpDir.resolve("autosar.profile.uml")
            val cmd = KumlCli()
            val result = cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            result.output shouldContain "Exported profile UML"
        }

        test("export --format profile-uml returns SCRIPT_ERROR when script returns non-KumlProfile") {
            if (!emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-err-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeUmlScript(tmpDir)
            val outputFile = tmpDir.resolve("bad.profile.uml")
            val cmd = KumlCli()
            val result = cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
        }

        test("export --format profile-uml returns FORMAT_NOT_AVAILABLE when EMF not on classpath") {
            // This test is only meaningful when EMF is NOT available — skip if it is.
            if (emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-unavail-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val outputFile = tmpDir.resolve("autosar.profile.uml")
            val cmd = KumlCli()
            val result = cmd.test("export --format profile-uml ${scriptFile.absolutePath} -o ${outputFile.absolutePath}")
            result.statusCode shouldBe ExitCodes.FORMAT_NOT_AVAILABLE
        }

        test("deriveOutputFile produces .profile.uml extension for profile-uml format") {
            // Verify via the actual CLI by omitting -o and checking the default output path
            if (!emfAvailable) return@test
            val tmpDir = Files.createTempDirectory("kuml-profile-cli-deriveext-").toFile()
            tmpDir.deleteOnExit()
            val scriptFile = writeProfileScript(tmpDir)
            val expectedOutput = tmpDir.resolve("my-profile.profile.uml")
            val cmd = KumlCli()
            cmd.test("export --format profile-uml ${scriptFile.absolutePath}")
            expectedOutput.exists() shouldBe true
        }
    })

private infix fun String.shouldNotContain(expected: String): Unit =
    assert(!this.contains(expected)) { "Expected string NOT to contain '$expected', but it did.\nActual: $this" }
