package dev.kuml.widget.compose

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class EventPayloadsTest :
    FunSpec({

        fun invalid(json: String): PayloadParseResult.Invalid = EventPayloads.parse(json).shouldBeInstanceOf<PayloadParseResult.Invalid>()

        fun nested(depth: Int): String = "{\"a\":".repeat(depth - 1) + "[1]" + "}".repeat(depth - 1)

        test("empty and blank input is a valid empty payload") {
            EventPayloads.parse("") shouldBe PayloadParseResult.Valid(kotlinx.serialization.json.JsonObject(emptyMap()))
            EventPayloads.parse("   \n ").shouldBeInstanceOf<PayloadParseResult.Valid>()
        }

        test("a plain object parses") {
            val r = EventPayloads.parse("""{"amount": 10, "who": "x"}""").shouldBeInstanceOf<PayloadParseResult.Valid>()
            r.payload.keys shouldBe setOf("amount", "who")
        }

        test("exactly 16 KiB is valid, one byte more is TOO_LARGE") {
            val prefix = """{"k":""""
            val suffix = "\"}"
            val fill = EventPayloads.MAX_BYTES - prefix.length - suffix.length
            EventPayloads.parse(prefix + "a".repeat(fill) + suffix).shouldBeInstanceOf<PayloadParseResult.Valid>()
            invalid(prefix + "a".repeat(fill + 1) + suffix).error shouldBe PayloadError.TOO_LARGE
        }

        test("the limit counts UTF-8 bytes, not characters") {
            val prefix = """{"k":""""
            val suffix = "\"}"
            // Each "ä" is 2 bytes: the character count stays below the limit, the byte count does not.
            val fill = (EventPayloads.MAX_BYTES - prefix.length - suffix.length) / 2 + 1
            val json = prefix + "ä".repeat(fill) + suffix
            (json.length < EventPayloads.MAX_BYTES) shouldBe true
            invalid(json).error shouldBe PayloadError.TOO_LARGE
        }

        test("depth 8 is valid and depth 9 is TOO_DEEP with a position") {
            EventPayloads.parse(nested(8)).shouldBeInstanceOf<PayloadParseResult.Valid>()
            val r = invalid(nested(9))
            r.error shouldBe PayloadError.TOO_DEEP
            r.position.shouldNotBeNull()
        }

        test("a long run of opening brackets is rejected before the parser sees it") {
            invalid("[".repeat(10_000)).error shouldBe PayloadError.TOO_DEEP
        }

        test("brackets inside strings do not count towards the depth") {
            val inside = "[".repeat(50)
            EventPayloads.parse("""{"s": "$inside"}""").shouldBeInstanceOf<PayloadParseResult.Valid>()
            EventPayloads.parse("""{"s": "a\" [[[[[[[[[[[[ b"}""").shouldBeInstanceOf<PayloadParseResult.Valid>()
        }

        test("top-level array, number and string are NOT_AN_OBJECT") {
            invalid("[1,2]").error shouldBe PayloadError.NOT_AN_OBJECT
            invalid("42").error shouldBe PayloadError.NOT_AN_OBJECT
            invalid("\"x\"").error shouldBe PayloadError.NOT_AN_OBJECT
        }

        test("reserved top-level keys are rejected, nested ones are fine") {
            for (key in listOf("name", "id", "timestamp")) {
                val r = invalid("""{"$key": 1}""")
                r.error shouldBe PayloadError.RESERVED_KEY
                r.detail shouldBe key
            }
            EventPayloads.parse("""{"o": {"name": 1}}""").shouldBeInstanceOf<PayloadParseResult.Valid>()
        }

        test("a syntax error is SYNTAX and carries a readable message") {
            val r = invalid("""{"a": }""")
            r.error shouldBe PayloadError.SYNTAX
            r.message.startsWith("Invalid JSON") shouldBe true
        }
    })
