package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppSettingsTest : FunSpec({

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    test("DEFAULT values are correct") {
        val s = AppSettings.DEFAULT
        s.schemaVersion shouldBe 1
        s.theme shouldBe "plain"
        s.language shouldBe "en"
        s.recentFiles shouldBe emptyList()
        s.lastDir shouldBe null
        s.windowWidth shouldBe 1200
        s.windowHeight shouldBe 800
        s.windowX shouldBe -1
        s.windowY shouldBe -1
    }

    test("JSON round-trip preserves all fields") {
        val original = AppSettings(
            schemaVersion = 1,
            theme = "dark",
            language = "de",
            recentFiles = listOf("/home/user/diagram.kuml.kts"),
            lastDir = "/home/user",
            windowWidth = 1400,
            windowHeight = 900,
            windowX = 100,
            windowY = 200,
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        decoded shouldBe original
    }

    test("ignoreUnknownKeys — JSON with extra field decodes without error") {
        val jsonWithExtra = """
            {
                "schemaVersion": 1,
                "theme": "blueprint",
                "language": "en",
                "recentFiles": [],
                "lastDir": null,
                "windowWidth": 1200,
                "windowHeight": 800,
                "windowX": -1,
                "windowY": -1,
                "unknownFutureField": "some-value"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<AppSettings>(jsonWithExtra)
        decoded.theme shouldBe "blueprint"
    }

    test("JSON without schemaVersion field defaults to 1") {
        val jsonWithoutVersion = """
            {
                "theme": "plain",
                "language": "en",
                "recentFiles": [],
                "lastDir": null,
                "windowWidth": 1200,
                "windowHeight": 800,
                "windowX": -1,
                "windowY": -1
            }
        """.trimIndent()
        val decoded = json.decodeFromString<AppSettings>(jsonWithoutVersion)
        decoded.schemaVersion shouldBe 1
    }
})
