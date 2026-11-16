@file:Suppress("RequireNamedArguments")

package dev.kuml.uml

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.core.model.note.isHint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class NotePlacementJsonTest :
    FunSpec(body = {
        val plain = Json
        val withDefaults = Json { encodeDefaults = true }

        test("old JSON without a placement field decodes to null") {
            plain.decodeFromString<KumlNote>("""{"id":"n1","body":"t"}""").placement shouldBe null
            plain.decodeFromString<UmlComment>("""{"id":"c1","body":"t"}""").placement shouldBe null
        }

        test("a null placement is never written, even with encodeDefaults") {
            for (json in listOf(plain, withDefaults)) {
                json.encodeToString(KumlNote.serializer(), KumlNote(id = "n1", body = "t")) shouldNotContain "placement"
                json.encodeToString(UmlComment.serializer(), UmlComment(id = "c1", body = "t")) shouldNotContain "placement"
            }
        }

        test("every placement value survives a round trip and is written, NEAR included") {
            for (json in listOf(plain, withDefaults)) {
                for (p in NotePlacement.entries) {
                    val note = KumlNote(id = "n1", body = "t", placement = p)
                    val text = json.encodeToString(KumlNote.serializer(), note)
                    text shouldContain "\"placement\":\"${p.name}\""
                    json.decodeFromString<KumlNote>(text) shouldBe note
                    val c = UmlComment(id = "c1", body = "t", placement = p)
                    json.decodeFromString<UmlComment>(json.encodeToString(UmlComment.serializer(), c)) shouldBe c
                }
            }
        }

        test("placement takes part in equality") {
            (KumlNote("n", "b", placement = NotePlacement.ABOVE) == KumlNote("n", "b")) shouldBe false
        }

        test("isHint is true only for the four sides") {
            NotePlacement.entries.filter { it.isHint() } shouldBe
                listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)
            (null as NotePlacement?).isHint() shouldBe false
        }
    })
