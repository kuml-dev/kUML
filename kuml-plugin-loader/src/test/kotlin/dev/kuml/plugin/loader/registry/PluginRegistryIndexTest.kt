package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
                  "kumlVersionRange": ">=0.13.0",
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

        // ── search ──────────────────────────────────────────────────────────────

        "search: blank query returns all plugins" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.search("") shouldHaveSize 2
        }

        "search: whitespace-only query returns all plugins" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.search("   ") shouldHaveSize 2
        }

        "search: matches by id substring (case-insensitive)" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val results = index.search("elk")
            results shouldHaveSize 1
            results[0].id shouldBe "dev.kuml.plugin.elk-layout"
        }

        "search: matches by name (case-insensitive)" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val results = index.search("BRANDING")
            results shouldHaveSize 1
            results[0].id shouldBe "dev.kuml.plugin.pdv-theme"
        }

        "search: matches by category" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val results = index.search("layout")
            results shouldHaveSize 1
            results[0].id shouldBe "dev.kuml.plugin.elk-layout"
        }

        "search: no match returns empty list" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            index.search("zzz-does-not-exist") shouldHaveSize 0
        }

        // ── kumlVersionRange ─────────────────────────────────────────────────────

        "kumlVersionRange: parsed from JSON when present" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val pdv = index.find("dev.kuml.plugin.pdv-theme")
            pdv?.kumlVersionRange shouldBe ">=0.13.0"
        }

        "kumlVersionRange: defaults to empty string when absent in JSON" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val elk = index.find("dev.kuml.plugin.elk-layout")
            elk?.kumlVersionRange shouldBe ""
        }

        // ── V3.1.12: new fields in index entries ─────────────────────────────

        "V3.1.12: index with new fields in one entry parses without error" {
            val jsonWithStats =
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
                      "downloadCount": 500,
                      "rating": 4.7,
                      "ratingCount": 8,
                      "reviews": [
                        { "author": "anna", "rating": 5, "comment": "Great!", "date": "2026-06-01" }
                      ]
                    },
                    {
                      "id": "dev.kuml.plugin.elk-layout",
                      "category": "layout",
                      "name": "ELK Layout Engine",
                      "version": "2.0.0",
                      "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
                      "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/"
                    }
                  ]
                }
                """.trimIndent()
            val index = json.decodeFromString<PluginRegistryIndex>(jsonWithStats)
            index.plugins shouldHaveSize 2
        }

        "V3.1.12: entry with new fields has correct downloadCount" {
            val jsonWithStats =
                """
                {
                  "schemaVersion": 1,
                  "plugins": [
                    {
                      "id": "dev.kuml.plugin.pdv-theme",
                      "category": "theme",
                      "name": "PdV Branding Theme",
                      "version": "1.0.0",
                      "manifest": "m",
                      "downloads": "d",
                      "downloadCount": 500,
                      "rating": 4.7,
                      "ratingCount": 8,
                      "reviews": [
                        { "author": "anna", "rating": 5, "comment": "Great!", "date": "2026-06-01" }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            val index = json.decodeFromString<PluginRegistryIndex>(jsonWithStats)
            val pdv = index.find("dev.kuml.plugin.pdv-theme")
            pdv?.downloadCount shouldBe 500L
        }

        "V3.1.12: entry without new fields defaults to zero / null / empty" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val elk = index.find("dev.kuml.plugin.elk-layout")
            elk?.downloadCount shouldBe 0L
            elk?.rating shouldBe null
            elk?.ratingCount shouldBe 0
            elk?.reviews shouldBe emptyList()
        }

        "V3.1.12: search and find still work when entries carry new fields" {
            val jsonWithStats =
                """
                {
                  "schemaVersion": 1,
                  "plugins": [
                    {
                      "id": "dev.kuml.plugin.pdv-theme",
                      "category": "theme",
                      "name": "PdV Branding Theme",
                      "version": "1.0.0",
                      "manifest": "m",
                      "downloads": "d",
                      "downloadCount": 500,
                      "rating": 4.7,
                      "ratingCount": 8,
                      "reviews": []
                    },
                    {
                      "id": "dev.kuml.plugin.elk-layout",
                      "category": "layout",
                      "name": "ELK Layout Engine",
                      "version": "2.0.0",
                      "manifest": "m2",
                      "downloads": "d2"
                    }
                  ]
                }
                """.trimIndent()
            val index = json.decodeFromString<PluginRegistryIndex>(jsonWithStats)
            index.search("theme") shouldHaveSize 1
            index.find("dev.kuml.plugin.elk-layout") shouldNotBe null
        }

        // ── V3.1.14: backward compat — legacy signaturePublicKey in index ────

        "V3.1.14: legacy signaturePublicKey field is migrated to signingKeys" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val pdv = index.find("dev.kuml.plugin.pdv-theme")
            pdv?.signingKeys?.shouldHaveSize(1)
            pdv?.signingKeys?.get(0)?.keyId shouldBe "legacy"
        }

        "V3.1.14: legacy null signaturePublicKey results in empty signingKeys" {
            val index = json.decodeFromString<PluginRegistryIndex>(sampleJson)
            val elk = index.find("dev.kuml.plugin.elk-layout")
            elk?.signingKeys?.shouldBeEmpty()
        }

        "V3.1.14: find and search still work when entries carry signingKeys" {
            val jsonWithSigningKeys =
                """
                {
                  "schemaVersion": 1,
                  "baseUrl": "https://plugins.kuml.dev",
                  "plugins": [
                    {
                      "id": "dev.kuml.plugin.pdv-theme",
                      "category": "theme",
                      "name": "PdV Branding Theme",
                      "version": "2.0.0",
                      "manifest": "m",
                      "downloads": "d",
                      "signingKeys": [
                        {
                          "publicKey": "MCowBQYDK2VwAyEAkey==",
                          "keyId": "2026-primary",
                          "validFrom": "2026-01-01",
                          "status": "ACTIVE"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            val index = json.decodeFromString<PluginRegistryIndex>(jsonWithSigningKeys)
            index.find("dev.kuml.plugin.pdv-theme") shouldNotBe null
            index.search("theme") shouldHaveSize 1
            index.find("dev.kuml.plugin.pdv-theme")?.signingKeys?.shouldHaveSize(1)
        }
    })
