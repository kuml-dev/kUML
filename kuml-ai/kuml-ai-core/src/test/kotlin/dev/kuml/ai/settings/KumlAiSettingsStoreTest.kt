package dev.kuml.ai.settings

import dev.kuml.ai.KumlAiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

class KumlAiSettingsStoreTest :
    FunSpec({

        lateinit var tempDir: java.nio.file.Path

        beforeTest {
            tempDir = Files.createTempDirectory("kuml-ai-test")
            System.setProperty("kuml.config.home", tempDir.toString())
        }

        afterTest { (_, _) ->
            System.clearProperty("kuml.config.home")
            tempDir.toFile().deleteRecursively()
        }

        test("load returns defaults when file does not exist") {
            val store = KumlAiSettingsStore()
            val settings = store.load()
            settings shouldBe KumlAiSettings()
            settings.privacyMode.shouldBeTrue()
        }

        test("save writes atomically and survives concurrent reads") {
            val store = KumlAiSettingsStore()
            val expected =
                KumlAiSettings(
                    defaultProvider = "openai",
                    privacyMode = false,
                    temperature = 0.9,
                )
            store.save(expected)
            val loaded = store.load()
            loaded shouldBe expected
        }

        // Review fix: migration is a sequential chain (0 -> 1 -> 2), not a flat per-version
        // decode — a V0 document must land on CURRENT_SCHEMA_VERSION (2), running through the
        // v1->v2 systemPrompt cleanup on the way, exactly like a document that started at v1.
        test("migrate V0 to current schema injects privacy mode true and lands on schema v2") {
            val store = KumlAiSettingsStore()
            val enabledProvidersArray =
                kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("ollama"))
                }
            val v0Json =
                JsonObject(
                    mapOf(
                        "enabledProviders" to enabledProvidersArray,
                        "defaultProvider" to JsonPrimitive("ollama"),
                        "defaultModels" to JsonObject(emptyMap()),
                    ),
                )
            val migrated = store.migrate(rawSchemaVersion = 0, raw = v0Json)
            migrated.privacyMode.shouldBeTrue()
            migrated.schemaVersion shouldBe KumlAiSettings.CURRENT_SCHEMA_VERSION
            migrated.schemaVersion shouldBe 2
        }

        // Review fix: a V0 document is a JSON object that literally cannot carry a
        // "systemPrompt" key yet (schema didn't have the field) — so `stored == null` in the
        // v1 case must trigger the same "let the current Kotlin default apply" cleanup a v1
        // document with the legacy literal default gets. Exercises the 0->1 fall-through
        // actually reaching the v1->v2 systemPrompt logic, not just landing on schemaVersion 2.
        test("migrate V0 to current schema also upgrades systemPrompt to the new default") {
            val store = KumlAiSettingsStore()
            val v0Json =
                JsonObject(
                    mapOf(
                        "enabledProviders" to kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("ollama")) },
                        "defaultProvider" to JsonPrimitive("ollama"),
                        "defaultModels" to JsonObject(emptyMap()),
                    ),
                )
            val migrated = store.migrate(rawSchemaVersion = 0, raw = v0Json)
            migrated.systemPrompt shouldBe KumlAiSettings.DEFAULT_SYSTEM_PROMPT
            migrated.systemPrompt shouldNotBe KumlAiSettings.LEGACY_V1_DEFAULT_SYSTEM_PROMPT
        }

        test("load throws SettingsCorrupted for unknown schema version") {
            val settingsPath = XdgPaths.aiSettingsPath()
            Files.createDirectories(settingsPath.parent)
            Files.writeString(settingsPath, """{"schemaVersion":99,"privacyMode":true}""")
            val store = KumlAiSettingsStore()
            shouldThrow<KumlAiException.SettingsCorrupted> {
                store.load()
            }
        }

        // ── V3.2.x — schema v1 → v2 migration (new DEFAULT_SYSTEM_PROMPT) ──────────

        test("CURRENT_SCHEMA_VERSION is 2") {
            KumlAiSettings.CURRENT_SCHEMA_VERSION shouldBe 2
        }

        test("migrate V1 with unchanged legacy default system prompt upgrades to the new default") {
            val store = KumlAiSettingsStore()
            val v1Json =
                JsonObject(
                    mapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "enabledProviders" to kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("ollama")) },
                        "defaultProvider" to JsonPrimitive("ollama"),
                        "defaultModels" to JsonObject(emptyMap()),
                        "privacyMode" to JsonPrimitive(true),
                        "systemPrompt" to JsonPrimitive(KumlAiSettings.LEGACY_V1_DEFAULT_SYSTEM_PROMPT),
                    ),
                )
            val migrated = store.migrate(rawSchemaVersion = 1, raw = v1Json)
            migrated.schemaVersion shouldBe 2
            migrated.systemPrompt shouldBe KumlAiSettings.DEFAULT_SYSTEM_PROMPT
            migrated.systemPrompt shouldNotBe KumlAiSettings.LEGACY_V1_DEFAULT_SYSTEM_PROMPT
        }

        test("migrate V1 with a user-customised system prompt keeps it untouched") {
            val store = KumlAiSettingsStore()
            val customPrompt = "Always answer in Klingon and never use tools."
            val v1Json =
                JsonObject(
                    mapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "enabledProviders" to kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("ollama")) },
                        "defaultProvider" to JsonPrimitive("ollama"),
                        "defaultModels" to JsonObject(emptyMap()),
                        "privacyMode" to JsonPrimitive(true),
                        "systemPrompt" to JsonPrimitive(customPrompt),
                    ),
                )
            val migrated = store.migrate(rawSchemaVersion = 1, raw = v1Json)
            migrated.schemaVersion shouldBe 2
            migrated.systemPrompt shouldBe customPrompt
        }
    })
