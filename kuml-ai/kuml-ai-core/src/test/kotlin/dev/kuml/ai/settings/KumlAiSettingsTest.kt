package dev.kuml.ai.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class KumlAiSettingsTest :
    FunSpec({

        test("default settings has privacy mode enabled by default") {
            val settings = KumlAiSettings()
            settings.privacyMode.shouldBeTrue()
        }

        test("settings serialize and deserialize round-trip is identical") {
            val original =
                KumlAiSettings(
                    enabledProviders = setOf("openai", "ollama"),
                    defaultProvider = "openai",
                    privacyMode = false,
                    temperature = 0.7,
                )
            val json = KumlAiSettingsStore.DEFAULT_JSON
            val serialized = json.encodeToString(KumlAiSettings.serializer(), original)
            val deserialized = json.decodeFromString(KumlAiSettings.serializer(), serialized)
            deserialized shouldBe original
        }

        test("unknown JSON fields are tolerated for forward compat") {
            val jsonWithUnknown =
                """
                {
                    "schemaVersion": 1,
                    "enabledProviders": ["ollama"],
                    "defaultProvider": "ollama",
                    "defaultModels": {},
                    "privacyMode": true,
                    "temperature": 0.2,
                    "futureField": "this should be ignored"
                }
                """.trimIndent()
            val settings =
                KumlAiSettingsStore.DEFAULT_JSON.decodeFromString(
                    KumlAiSettings.serializer(),
                    jsonWithUnknown,
                )
            settings.defaultProvider shouldBe "ollama"
            settings.privacyMode.shouldBeTrue()
        }
    })
