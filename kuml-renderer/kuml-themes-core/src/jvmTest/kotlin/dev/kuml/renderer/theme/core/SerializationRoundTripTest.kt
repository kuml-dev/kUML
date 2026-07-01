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
    })
