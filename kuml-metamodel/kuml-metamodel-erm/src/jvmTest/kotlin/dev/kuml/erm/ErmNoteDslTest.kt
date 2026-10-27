package dev.kuml.erm

import dev.kuml.erm.constraint.ErmNoteChecker
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class ErmNoteDslTest :
    FunSpec(body = {

        test("free-standing note, anchors on entity and view") {
            val model =
                ermModel(name = "M") {
                    val e = entity(name = "A") { id(name = "id", type = ErmDataType.Integer(64)) }
                    val v = view(name = "V", query = "select 1")
                    note(text = "free")
                    note(text = "anchored", e, v)
                }
            model.notes.map { it.id } shouldContainExactly listOf("note_0", "note_1")
            model.noteLinks.map { it.link.targetId } shouldContainExactly listOf("entity_0", "view_0")
            ErmNoteChecker.check(model) shouldBe emptyList()
        }

        test("anchoring an attribute, a relationship or an unknown id throws on build") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    ermModel(name = "M") {
                        val e = entity(name = "A") { id(name = "id", type = ErmDataType.Integer(64)) }
                        val r = relationship(from = e, to = e)
                        note(text = "x", r, id = "n")
                    }
                }
            ex.message!! shouldContain "n"
            shouldThrow<IllegalArgumentException> { ermModel(name = "M") { note(text = "x", "nope") } }
        }

        test("json round trip; old json decodes") {
            val model =
                ermModel(name = "M") {
                    val e = entity(name = "A") { id(name = "id", type = ErmDataType.Integer(64)) }
                    note(text = "a \"q\"\n<b>", e)
                }
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(ErmModel.serializer(), json.encodeToString(ErmModel.serializer(), model)) shouldBe model
            json.decodeFromString(ErmModel.serializer(), """{"name":"O"}""").notes shouldHaveSize 0
        }

        test("checker distinguishes unknown from unsupported anchors") {
            val base =
                ermModel(name = "M") {
                    val e = entity(name = "A") { id(name = "id", type = ErmDataType.Integer(64)) }
                    relationship(from = e, to = e)
                    note(text = "x", e, id = "n")
                }
            val bad =
                base.copy(
                    noteLinks =
                        base.noteLinks +
                            dev.kuml.erm.model
                                .ErmNoteLink(
                                    link =
                                        dev.kuml.core.model.note
                                            .KumlNoteLink(id = "l1", noteId = "n", targetId = "ghost"),
                                ) +
                            dev.kuml.erm.model
                                .ErmNoteLink(
                                    link =
                                        dev.kuml.core.model.note
                                            .KumlNoteLink(id = "l2", noteId = "n", targetId = "rel_0"),
                                ),
                )
            ErmNoteChecker.check(bad).map { it.code } shouldContainExactly
                listOf(ErmNoteChecker.ANCHOR_UNKNOWN, ErmNoteChecker.ANCHOR_UNSUPPORTED)
        }
    })
