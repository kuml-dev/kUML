package dev.kuml.plugin.loader.manifest

import dev.kuml.plugin.loader.error.ManifestParseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PluginManifestParserTest :
    FunSpec({

        val minimalJson =
            """
            {
              "schemaVersion": 1,
              "id": "com.example.my-plugin",
              "name": "My Plugin",
              "version": "1.0.0",
              "kumlVersionRange": ">=3.0.0",
              "extensions": [
                { "category": "theme", "implementation": "com.example.MyThemePlugin", "id": "my-theme" }
              ]
            }
            """.trimIndent()

        test("parses a valid minimal JSON manifest") {
            val manifest = PluginManifestParser.parse(minimalJson)
            manifest.id shouldBe "com.example.my-plugin"
            manifest.name shouldBe "My Plugin"
            manifest.version shouldBe "1.0.0"
            manifest.kumlVersionRange shouldBe ">=3.0.0"
            manifest.extensions.size shouldBe 1
            manifest.extensions[0].category shouldBe "theme"
            manifest.extensions[0].implementation shouldBe "com.example.MyThemePlugin"
            manifest.extensions[0].id shouldBe "my-theme"
        }

        test("optional fields have correct defaults") {
            val manifest = PluginManifestParser.parse(minimalJson)
            manifest.schemaVersion shouldBe 1
            manifest.permissions shouldBe emptyList()
            manifest.maintainer shouldBe ""
            manifest.homepage shouldBe ""
            manifest.licenseSpdx shouldBe "Apache-2.0"
            manifest.signature shouldBe null
        }

        test("unknown JSON keys are ignored (forward compatibility)") {
            val json = minimalJson.replace("}", """, "unknownFutureKey": "ignored" }""")
            val manifest = PluginManifestParser.parse(json)
            manifest.id shouldBe "com.example.my-plugin"
        }

        test("schemaVersion != 1 throws ManifestParseException") {
            val json = minimalJson.replace(""""schemaVersion": 1""", """"schemaVersion": 2""")
            val ex = shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
            ex.message shouldContain "Unsupported schema version"
            ex.message shouldContain "2"
        }

        test("blank id throws ManifestParseException") {
            val json = minimalJson.replace(""""id": "com.example.my-plugin"""", """"id": "  """")
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "'id'"
        }

        test("blank name throws ManifestParseException") {
            val json = minimalJson.replace(""""name": "My Plugin"""", """"name": "  """")
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "'name'"
        }

        test("empty extensions array throws ManifestParseException") {
            val json =
                """
                {
                  "schemaVersion": 1,
                  "id": "com.example.my-plugin",
                  "name": "My Plugin",
                  "version": "1.0.0",
                  "kumlVersionRange": ">=3.0.0",
                  "extensions": []
                }
                """.trimIndent()
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "'extensions'"
        }

        test("invalid extension category throws ManifestParseException") {
            val json = minimalJson.replace(""""category": "theme"""", """"category": "paint"""")
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "paint"
        }

        test("blank implementation in extension throws ManifestParseException") {
            val json = minimalJson.replace(""""implementation": "com.example.MyThemePlugin"""", """"implementation": "  """")
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "'implementation'"
        }

        test("blank extension id throws ManifestParseException") {
            val json = minimalJson.replace(""""id": "my-theme"""", """"id": "  """")
            shouldThrow<ManifestParseException> { PluginManifestParser.parse(json) }
                .message shouldContain "Extension entry 'id'"
        }

        test("invalid JSON throws ManifestParseException") {
            shouldThrow<ManifestParseException> { PluginManifestParser.parse("not valid json {{{{") }
        }

        test("permissions list is parsed correctly") {
            val json =
                minimalJson.replace(
                    """"kumlVersionRange": ">=3.0.0"""",
                    """"kumlVersionRange": ">=3.0.0", "permissions": ["FS_READ", "NETWORK_HTTP"]""",
                )
            val manifest = PluginManifestParser.parse(json)
            manifest.permissions shouldBe listOf("FS_READ", "NETWORK_HTTP")
        }

        test("all optional fields are parsed when present") {
            val json =
                minimalJson.replace(
                    """"kumlVersionRange": ">=3.0.0"""",
                    """
                    "kumlVersionRange": ">=3.0.0",
                    "maintainer": "Alice",
                    "homepage": "https://example.com",
                    "licenseSpdx": "MIT",
                    "signature": "abc123"
                    """.trimIndent(),
                )
            val manifest = PluginManifestParser.parse(json)
            manifest.maintainer shouldBe "Alice"
            manifest.homepage shouldBe "https://example.com"
            manifest.licenseSpdx shouldBe "MIT"
            manifest.signature shouldBe "abc123"
        }
    })
