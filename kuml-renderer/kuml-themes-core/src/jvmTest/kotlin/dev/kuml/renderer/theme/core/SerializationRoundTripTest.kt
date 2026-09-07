package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SerializationRoundTripTest :
    FunSpec({

        test("KumlTheme round-trips through Json encode and decode") {
            val original = PlainTheme()
            val json = Json.encodeToString(original)
            val decoded = Json.decodeFromString<KumlTheme>(json)
            decoded shouldBe original
        }

        test("KumlColor round-trips through Json encode and decode") {
            val color = KumlColor(0xFF6600)
            val json = Json.encodeToString(color)
            val decoded = Json.decodeFromString<KumlColor>(json)
            decoded shouldBe color
        }

        // V3.x — Live-Simulation: activeStateStroke is nullable-with-default, added at the end
        // of KumlColors' parameter list. Old theme JSON without the field must still decode
        // (via the default); a theme that DOES set it must round-trip the value itself.
        test("KumlColors round-trips with activeStateStroke unset (old theme JSON shape)") {
            val original = PlainTheme()
            original.colors.activeStateStroke shouldBe null
            val json = Json.encodeToString(original)
            val decoded = Json.decodeFromString<KumlTheme>(json)
            decoded shouldBe original
        }

        test("KumlColors round-trips with activeStateStroke set") {
            val original = PlainTheme().let { it.copy(colors = it.colors.copy(activeStateStroke = KumlColor(0xFF6B35))) }
            val json = Json.encodeToString(original)
            val decoded = Json.decodeFromString<KumlTheme>(json)
            decoded shouldBe original
            decoded.colors.activeStateStroke shouldBe KumlColor(0xFF6B35)
        }
    })
