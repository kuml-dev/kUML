package dev.kuml.plugin.loader.scan

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class PluginScanPathTest :
    FunSpec({

        test("userPluginDir path contains .kuml/plugins") {
            val path = PluginScanPath.userPluginDir.toString()
            path shouldContain ".kuml"
            path shouldContain "plugins"
        }

        test("systemPluginDir is null when KUML_HOME env var is not set") {
            // Only assertable when KUML_HOME is indeed absent; skip if set in CI
            if (System.getenv("KUML_HOME") == null) {
                PluginScanPath.systemPluginDir shouldBe null
            }
        }

        test("jarsIn returns empty list for non-existent directory") {
            val nonExistent =
                File(System.getProperty("java.io.tmpdir"), "kuml-plugin-scan-test-missing-${System.nanoTime()}")
                    .toPath()
            PluginScanPath.jarsIn(nonExistent).shouldBeEmpty()
        }

        test("jarsIn returns JAR files in an existing directory") {
            val tmpDir = Files.createTempDirectory("kuml-plugin-scan-test")
            try {
                File(tmpDir.toFile(), "plugin-a.jar").createNewFile()
                File(tmpDir.toFile(), "plugin-b.jar").createNewFile()
                val jars = PluginScanPath.jarsIn(tmpDir)
                jars shouldHaveSize 2
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("jarsIn ignores non-JAR files") {
            val tmpDir = Files.createTempDirectory("kuml-plugin-scan-test")
            try {
                File(tmpDir.toFile(), "plugin.jar").createNewFile()
                File(tmpDir.toFile(), "readme.txt").createNewFile()
                File(tmpDir.toFile(), "plugin.zip").createNewFile()
                val jars = PluginScanPath.jarsIn(tmpDir)
                jars shouldHaveSize 1
                jars[0].name shouldBe "plugin.jar"
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
    })
