package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

class PluginRegistryIndexTest :
    StringSpec({

        val json = Json { ignoreUnknownKeys = true }

        val sampleJson =
            """
            {
              "schemaVersion": 1,
              "baseUrl": "https://plugins.kuml.dev",
              "plugins": [
                {
                  "id": "dev.kuml.plugin.pdv-theme",
                  "category": "theme",
                  "name": "PdV Branding Theme",
                  "version": "1.0.0",
                  "manifest": "plugins/dev.kuml.plugin.pdv-theme/kuml-plugin.json",
                  "downloads": "plugins/dev.kuml.plugin.pdv-theme/releases/",
                  "signaturePublicKey": "MCowBQYDK2VwAyEA...",
                  "maintainer": "Partei der Vernunft"
                },
                {
                  "id": "dev.kuml.plugin.elk-layout",
                  "category": "layout",
                  "name": "ELK Layout Engine",
                  "version": "2.0.0",
                  "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
                  "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/",
                  "signaturePublicKey": null
                }
              ]
            }
            """.trimIndent()

        "parse index from JSON" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.plugins shouldHaveSize 2
            index.schemaVersion shouldBe 1
        }

        "find by id returns correct entry" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val entry = index.find("dev.kuml.plugin.pdv-theme")
            entry shouldNotBe null
            entry?.name shouldBe "PdV Branding Theme"
            entry?.category shouldBe "theme"
        }

        "find by unknown id returns null" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.find("not.a.real.plugin") shouldBe null
        }

        "byCategory returns matching entries" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val themes = index.byCategory("theme")
            themes shouldHaveSize 1
            themes[0].id shouldBe "dev.kuml.plugin.pdv-theme"
        }

        "byCategory for unknown category returns empty list" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.byCategory("nonexistent") shouldHaveSize 0
        }

        "empty index parses without error" {
            val emptyJson = """{"schemaVersion": 1, "plugins": []}"""
            val index = json.decodeFromString<PluginRegistryIndex>(emptyJson)
            index.plugins shouldHaveSize 0
        }
    })
