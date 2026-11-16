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

        // Note tone tokens: nullable with default at the end of KumlColors.
        test("old theme JSON decodes with all note tone tokens null and they are not emitted") {
            val original = PlainTheme()
            val json = Json.encodeToString(original)
            json.contains("noteInfo") shouldBe false
            val c = Json.decodeFromString<KumlTheme>(json).colors
            listOf(c.noteInfo, c.noteHint, c.noteSuccess, c.noteWarning, c.noteDanger, c.noteImportant).all { it == null } shouldBe true
        }

        test("a note tone token survives a Json round trip") {
            val original = PlainTheme().let { it.copy(colors = it.colors.copy(noteWarning = KumlColor(0x0055AA))) }
            val json = Json.encodeToString(original)
            json.contains("noteWarning") shouldBe true
            val decoded = Json.decodeFromString<KumlTheme>(json)
            decoded shouldBe original
            decoded.colors.noteWarning shouldBe KumlColor(0x0055AA)
            decoded.colors.noteInfo shouldBe null
        }
    })
