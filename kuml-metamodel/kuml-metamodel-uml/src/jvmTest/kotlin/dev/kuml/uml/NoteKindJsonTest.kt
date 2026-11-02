package dev.kuml.uml

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class NoteKindJsonTest :
    FunSpec(body = {
        val plain = Json
        val withDefaults = Json { encodeDefaults = true }

        test("there are 15 kinds, 14 of them with a symbol") {
            NoteKind.entries.size shouldBe 15
            NoteKind.entries.count { it.hasIcon } shouldBe 14
            NoteKind.NOTE.hasIcon shouldBe false
        }

        test("every KumlNote kind survives a JSON round trip") {
            for (json in listOf(plain, withDefaults)) {
                for (kind in NoteKind.entries) {
                    val note = KumlNote(id = "n1", body = "text", kind = kind)
                    json.decodeFromString<KumlNote>(json.encodeToString(KumlNote.serializer(), note)) shouldBe note
                }
            }
        }

        test("every UmlComment kind survives a JSON round trip") {
            for (kind in NoteKind.entries) {
                val c = UmlComment(id = "c1", body = "text", kind = kind)
                plain.decodeFromString<UmlComment>(plain.encodeToString(UmlComment.serializer(), c)) shouldBe c
            }
        }

        test("NOTE writes no kind field, even with encodeDefaults, so existing JSON is unchanged") {
            for (json in listOf(plain, withDefaults)) {
                json.encodeToString(KumlNote.serializer(), KumlNote(id = "n1", body = "t")) shouldNotContain "kind"
                json.encodeToString(UmlComment.serializer(), UmlComment(id = "c1", body = "t")) shouldNotContain "kind"
            }
        }

        test("a non-NOTE kind is written, and JSON without a kind field still decodes as NOTE") {
            plain.encodeToString(KumlNote.serializer(), KumlNote(id = "n1", body = "t", kind = NoteKind.WARNING)) shouldContain
                "\"kind\":\"WARNING\""
            plain.decodeFromString<KumlNote>("""{"id":"n1","body":"t"}""").kind shouldBe NoteKind.NOTE
            plain.decodeFromString<UmlComment>("""{"id":"c1","body":"t"}""").kind shouldBe NoteKind.NOTE
        }

        test("N3: a body with markup survives a JSON round trip byte for byte, KumlNote and UmlComment") {
            val text = "**b** *i* `c` [l](https://kuml.dev) \\* C:\\Users\n- a\n1. x\n1. y \$x"
            val note = KumlNote(id = "n1", body = text, kind = NoteKind.INFO)
            val noteJson = plain.encodeToString(KumlNote.serializer(), note)
            plain.decodeFromString<KumlNote>(noteJson) shouldBe note
            plain.encodeToString(KumlNote.serializer(), plain.decodeFromString<KumlNote>(noteJson)) shouldBe noteJson
            val comment = UmlComment(id = "c1", body = text, kind = NoteKind.INFO)
            val commentJson = plain.encodeToString(UmlComment.serializer(), comment)
            plain.decodeFromString<UmlComment>(commentJson) shouldBe comment
            plain.encodeToString(UmlComment.serializer(), plain.decodeFromString<UmlComment>(commentJson)) shouldBe commentJson
        }
    })
