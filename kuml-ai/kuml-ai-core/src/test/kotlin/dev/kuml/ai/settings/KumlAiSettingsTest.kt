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

        // V3.7.5 (review fix) — legacyKeychainNoticeDismissed must default to false, and a
        // schema-v2 file saved BEFORE this field existed (no key present at all) must decode to
        // false too, not fail or silently resurrect an already-dismissed notice. See the field's
        // KDoc: this is what lets the notice keep reappearing until the user actually dismisses
        // it, on every pre-existing installation.
        test("legacyKeychainNoticeDismissed defaults to false") {
            KumlAiSettings().legacyKeychainNoticeDismissed shouldBe false
        }

        test("a v2 settings file saved before legacyKeychainNoticeDismissed existed decodes to false") {
            val jsonWithoutField =
                """
                {
                    "schemaVersion": 2,
                    "enabledProviders": ["ollama"],
                    "defaultProvider": "ollama",
                    "defaultModels": {},
                    "privacyMode": true,
                    "temperature": 0.2
                }
                """.trimIndent()
            val settings =
                KumlAiSettingsStore.DEFAULT_JSON.decodeFromString(
                    KumlAiSettings.serializer(),
                    jsonWithoutField,
                )
            settings.legacyKeychainNoticeDismissed shouldBe false
        }

        test("legacyKeychainNoticeDismissed round-trips through serialization") {
            val original = KumlAiSettings(legacyKeychainNoticeDismissed = true)
            val json = KumlAiSettingsStore.DEFAULT_JSON
            val serialized = json.encodeToString(KumlAiSettings.serializer(), original)
            val deserialized = json.decodeFromString(KumlAiSettings.serializer(), serialized)
            deserialized.legacyKeychainNoticeDismissed shouldBe true
        }
    })
