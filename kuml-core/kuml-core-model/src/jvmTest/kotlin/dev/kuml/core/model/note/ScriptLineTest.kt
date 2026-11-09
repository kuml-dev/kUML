package dev.kuml.core.model.note

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScriptLineTest :
    FunSpec({
        test("outside a script there is no line") {
            callerScriptLine() shouldBe null
        }

        test("a note without a line serialises byte-identically to before, one with a line carries it") {
            val plain = Json.encodeToString(KumlNote(id = "n", body = "b"))
            plain shouldNotContain "sourceLine"
            val withLine = KumlNote(id = "n", body = "b", sourceLine = 12)
            val json = Json.encodeToString(withLine)
            Json.decodeFromString<KumlNote>(json).sourceLine shouldBe 12
            // Where a note was written is not part of its identity.
            withLine shouldBe KumlNote(id = "n", body = "b", sourceLine = 99)
            Json.decodeFromString<KumlNote>("""{"id":"n","body":"b"}""").sourceLine shouldBe null
        }
    })
