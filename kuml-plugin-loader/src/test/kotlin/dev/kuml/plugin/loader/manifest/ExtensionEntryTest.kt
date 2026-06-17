package dev.kuml.plugin.loader.manifest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExtensionEntryTest :
    FunSpec({

        test("ExtensionEntry holds category, implementation and id") {
            val entry =
                ExtensionEntry(
                    category = "theme",
                    implementation = "com.example.ThemePlugin",
                    id = "example-theme",
                )
            entry.category shouldBe "theme"
            entry.implementation shouldBe "com.example.ThemePlugin"
            entry.id shouldBe "example-theme"
        }

        test("all valid categories are accepted by parser") {
            val categories = listOf("theme", "renderer", "layout", "codegen", "reverse")
            val baseJson =
                """
                {
                  "schemaVersion": 1,
                  "id": "p",
                  "name": "P",
                  "version": "1.0.0",
                  "kumlVersionRange": ">=3.0.0",
                  "extensions": [{ "category": "%s", "implementation": "X", "id": "x" }]
                }
                """.trimIndent()
            categories.forEach { cat ->
                val manifest = PluginManifestParser.parse(baseJson.replace("%s", cat))
                manifest.extensions[0].category shouldBe cat
            }
        }

        test("ExtensionEntry data class equality works") {
            val a = ExtensionEntry("theme", "com.example.A", "a")
            val b = ExtensionEntry("theme", "com.example.A", "a")
            a shouldBe b
        }
    })
