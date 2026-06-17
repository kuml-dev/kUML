package dev.kuml.plugin.loader.loader

import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.error.ManifestParseException
import dev.kuml.plugin.loader.error.PluginLoadException
import dev.kuml.plugin.loader.error.VersionMismatchException
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.registry.PluginRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginLoaderTest :
    FunSpec({

        beforeEach { PluginRegistry.clearForTest() }

        fun makeJarWithoutManifest(): File {
            val jar = Files.createTempFile("no-manifest-plugin", ".jar").toFile()
            ZipOutputStream(jar.outputStream()).use { it.closeEntry() }
            return jar
        }

        fun makeJarWithManifest(json: String): File {
            val jar = Files.createTempFile("with-manifest-plugin", ".jar").toFile()
            ZipOutputStream(jar.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("kuml-plugin.json"))
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
            return jar
        }

        test("loadJar with JAR missing kuml-plugin.json throws PluginLoadException") {
            val jar = makeJarWithoutManifest()
            try {
                val ex =
                    shouldThrow<PluginLoadException> {
                        PluginLoader.loadJar(jar, PluginVersion(3, 0, 28))
                    }
                ex.message shouldContain "kuml-plugin.json"
            } finally {
                jar.delete()
            }
        }

        test("checkVersion: version inside range does not throw") {
            val manifest =
                PluginManifest(
                    id = "p",
                    name = "P",
                    version = "1.0.0",
                    kumlVersionRange = ">=3.0.0, <4.0.0",
                    extensions = listOf(ExtensionEntry("theme", "X", "x")),
                )
            shouldNotThrowAny {
                PluginLoader.checkVersion(manifest, PluginVersion(3, 0, 28))
            }
        }

        test("checkVersion: version outside range throws VersionMismatchException") {
            val manifest =
                PluginManifest(
                    id = "p",
                    name = "P",
                    version = "1.0.0",
                    kumlVersionRange = ">=3.0.0, <3.0.10",
                    extensions = listOf(ExtensionEntry("theme", "X", "x")),
                )
            val ex =
                shouldThrow<VersionMismatchException> {
                    PluginLoader.checkVersion(manifest, PluginVersion(4, 0, 0))
                }
            ex.pluginId shouldBe "p"
            ex.pluginVersionRange shouldBe ">=3.0.0, <3.0.10"
            ex.runtimeVersion shouldBe "4.0.0"
        }

        test("loadJar with invalid manifest JSON throws ManifestParseException (wrapped or direct)") {
            val jar = makeJarWithManifest("not valid json")
            try {
                shouldThrow<ManifestParseException> {
                    PluginLoader.loadJar(jar, PluginVersion(3, 0, 28))
                }
            } finally {
                jar.delete()
            }
        }

        test("loadJar with version mismatch throws VersionMismatchException") {
            val json =
                """
                {
                  "schemaVersion": 1,
                  "id": "old-plugin",
                  "name": "Old Plugin",
                  "version": "1.0.0",
                  "kumlVersionRange": ">=1.0.0, <2.0.0",
                  "extensions": [{ "category": "theme", "implementation": "X", "id": "x" }]
                }
                """.trimIndent()
            val jar = makeJarWithManifest(json)
            try {
                shouldThrow<VersionMismatchException> {
                    PluginLoader.loadJar(jar, PluginVersion(3, 0, 28))
                }
            } finally {
                jar.delete()
            }
        }

        test("loadBuiltInsViaServiceLoader runs without exception even with empty ServiceLoader") {
            // This just confirms the method runs; no built-in providers may be on the classpath here
            shouldNotThrowAny {
                PluginLoader.load(PluginVersion(3, 0, 28))
            }
        }
    })
