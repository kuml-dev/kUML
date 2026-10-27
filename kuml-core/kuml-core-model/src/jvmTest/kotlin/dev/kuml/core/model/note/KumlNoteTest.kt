package dev.kuml.core.model.note

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class KumlNoteTest :
    FunSpec(body = {

        test("empty body is ok") {
            KumlNote(id = "n1", body = "").body shouldBe ""
        }

        test("body at the limit is ok, above the limit throws without echoing the body") {
            KumlNote(id = "n1", body = "a".repeat(KumlNoteLimits.MAX_BODY_CHARS))
            val ex = shouldThrow<IllegalArgumentException> { KumlNote(id = "n1", body = "secret".repeat(20_000)) }
            ex.message!! shouldContain "n1"
            ex.message!! shouldNotContain "secret"
        }

        test("json decode of an oversized body throws") {
            val json = """{"id":"n1","body":"${"a".repeat(KumlNoteLimits.MAX_BODY_CHARS + 1)}"}"""
            shouldThrow<Exception> { Json.decodeFromString(KumlNote.serializer(), json) }
        }

        test("blank ids throw") {
            shouldThrow<IllegalArgumentException> { KumlNote(id = " ", body = "x") }
            shouldThrow<IllegalArgumentException> { KumlNoteLink(id = "", noteId = "a", targetId = "b") }
        }

        test("json round trip") {
            val n = KumlNote(id = "n1", body = "hello \"world\"\n<b>")
            Json.decodeFromString(KumlNote.serializer(), Json.encodeToString(KumlNote.serializer(), n)) shouldBe n
        }
    })
