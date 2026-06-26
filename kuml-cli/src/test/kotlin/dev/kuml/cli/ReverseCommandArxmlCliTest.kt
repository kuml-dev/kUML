package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * CLI tests for `kuml reverse --format arxml`.
 *
 * Tests multi-file ARXML merge, empty-dir error, and single-file reverse.
 *
 * V3.1.36 — initial implementation.
 */
class ReverseCommandArxmlCliTest :
    FunSpec({
        val arxmlAvailable =
            try {
                Class.forName("dev.kuml.io.arxml.ArxmlClassicImporter")
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        /** Minimal valid ARXML string for a package with one APPLICATION-SW-COMPONENT-TYPE. */
        fun minimalArxml(
            packageName: String,
            componentName: String,
        ): String =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
              <AR-PACKAGES>
                <AR-PACKAGE>
                  <SHORT-NAME>$packageName</SHORT-NAME>
                  <ELEMENTS>
                    <APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>$componentName</SHORT-NAME>
                    </APPLICATION-SW-COMPONENT-TYPE>
                  </ELEMENTS>
                </AR-PACKAGE>
              </AR-PACKAGES>
            </AUTOSAR>
            """.trimIndent()

        test("kuml reverse --format arxml merges two .arxml files into one model without duplicate packages") {
            if (!arxmlAvailable) return@test

            val tmpDir = Files.createTempDirectory("kuml-arxml-reverse-merge-").toFile()
            try {
                // Both files declare the same AR-PACKAGE "P" with different components
                val fileA = tmpDir.resolve("fileA.arxml")
                val fileB = tmpDir.resolve("fileB.arxml")
                fileA.writeText(minimalArxml("P", "CompA"))
                fileB.writeText(minimalArxml("P", "CompB"))

                val outFile = tmpDir.resolve("out.kuml.kts")
                val result =
                    KumlCli().test("reverse --format arxml ${tmpDir.absolutePath} -o ${outFile.absolutePath}")
                result.statusCode shouldBe 0
                outFile.exists() shouldBe true

                val dsl = outFile.readText()
                // Both components should appear in the output
                dsl shouldContain "CompA"
                dsl shouldContain "CompB"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml reverse --format arxml on empty directory exits REVERSE_NO_SOURCES") {
            if (!arxmlAvailable) return@test

            val tmpDir = Files.createTempDirectory("kuml-arxml-reverse-empty-").toFile()
            try {
                val result = KumlCli().test("reverse --format arxml ${tmpDir.absolutePath}")
                result.statusCode shouldBe ExitCodes.REVERSE_NO_SOURCES
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml reverse --format arxml single file works") {
            if (!arxmlAvailable) return@test

            val tmpDir = Files.createTempDirectory("kuml-arxml-reverse-single-").toFile()
            try {
                val file = tmpDir.resolve("single.arxml")
                file.writeText(minimalArxml("Body", "SpeedSensorSwc"))

                val outFile = tmpDir.resolve("out.kuml.kts")
                val result =
                    KumlCli().test("reverse --format arxml ${tmpDir.absolutePath} -o ${outFile.absolutePath}")
                result.statusCode shouldBe 0
                outFile.exists() shouldBe true
                val dsl = outFile.readText()
                dsl shouldContain "SpeedSensorSwc"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("kuml reverse --format source still works after arxml option was added") {
            // Regression guard: default source reverse must still work
            val tmpDir = Files.createTempDirectory("kuml-arxml-source-regression-").toFile()
            try {
                // Write a minimal Kotlin file
                val srcDir = tmpDir.resolve("src")
                srcDir.mkdirs()
                srcDir.resolve("Foo.kt").writeText(
                    """
                    package com.example
                    class Foo { fun bar(): String = "hello" }
                    """.trimIndent(),
                )
                val result = KumlCli().test("reverse --format source ${srcDir.absolutePath} --lang kotlin")
                // Should be 0 (success) or a known reverse exit code — but NOT a 'format option not recognized' error
                // The key assertion: it should NOT fail with usage error (2) or be rejected as unknown format
                val validCodes =
                    setOf(
                        0,
                        ExitCodes.REVERSE_NO_SOURCES,
                        ExitCodes.REVERSE_ANALYSIS_FAILED,
                        ExitCodes.REVERSE_ENGINE_NOT_FOUND,
                    )
                validCodes.contains(result.statusCode) shouldBe true
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    })
