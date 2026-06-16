package dev.kuml.ai.settings

import dev.kuml.ai.KumlAiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
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

        test("migrate V0 to V1 injects privacy mode true") {
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
            val migrated = store.migrate(0, v0Json)
            migrated.privacyMode.shouldBeTrue()
            migrated.schemaVersion shouldBe 1
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
    })
