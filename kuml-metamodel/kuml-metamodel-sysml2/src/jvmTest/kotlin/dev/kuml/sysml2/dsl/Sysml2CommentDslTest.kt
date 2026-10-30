package dev.kuml.sysml2.dsl

import dev.kuml.core.model.note.KumlNoteLimits
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.constraint.Sysml2NoteChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class Sysml2CommentDslTest :
    FunSpec(body = {

        test("comment and note are equivalent; free-standing, one and two anchors") {
            val model =
                sysml2Model(name = "M") {
                    val a = partDef(name = "A")
                    val b = partDef(name = "B")
                    comment(text = "free")
                    note(text = "one", "A")
                    comment(text = "two (handles)", firstAnchor = a, b)
                    comment(text = "two (ids)", "A", "B", id = "custom")
                }
            model.comments.map { it.id } shouldContainExactly listOf("comment-1", "comment-2", "comment-3", "custom")
            model.commentLinks.map { it.link.targetId } shouldContainExactly listOf("A", "A", "B", "A", "B")
            model.commentLinks.first().id shouldBe "note-link:comment-2::A"
        }

        test("unknown anchor builds (reported as KUML-NOTE-W001 with both ids), forward references via ids work") {
            val unknown = sysml2Model(name = "M") { comment(text = "x", "Nope", id = "n1") }
            val issue = Sysml2NoteChecker.check(unknown).single { it.code == "KUML-NOTE-W001" }
            issue.severity shouldBe dev.kuml.core.model.note.KumlNoteIssue.Severity.WARNING
            issue.message shouldContain "Anker unbekannt: Nope"
            issue.message shouldContain "n1"
            val model =
                sysml2Model(name = "M") {
                    comment(text = "fwd", "Later")
                    partDef(name = "Later")
                }
            model.commentLinks shouldHaveSize 1
        }

        test("comment inside a diagram block sets noteIds; include(comment) works") {
            val model =
                sysml2Model(name = "M") {
                    val a = partDef(name = "A")
                    val shared = comment(text = "shared", "A")
                    bdd(name = "D1") {
                        include(a)
                        comment(text = "local", firstAnchor = a)
                    }
                    bdd(name = "D2") { include(shared) }
                }
            val d1 = model.diagrams[0] as BdDiagram
            val d2 = model.diagrams[1] as BdDiagram
            d1.noteIds shouldHaveSize 1
            d1.elementIds shouldContainExactly listOf("A")
            d2.noteIds shouldContainExactly listOf("comment-1")
        }

        test("anchors on ibd part usages (features) are accepted") {
            val model =
                sysml2Model(name = "M") {
                    val e = partDef(name = "Engine")
                    val v = partDef(name = "Vehicle") { part(name = "engine", typeId = e.id) }
                    ibd(name = "W", owner = v) { comment(text = "engine note", "Vehicle::engine") }
                }
            (model.diagrams.single() as IbdDiagram).noteIds shouldHaveSize 1
        }

        test("oversized body is rejected by the hard limit") {
            shouldThrow<IllegalArgumentException> {
                sysml2Model(name = "M") { comment(text = "a".repeat(KumlNoteLimits.MAX_BODY_CHARS + 1)) }
            }
        }

        test("json round trip keeps comments; old json without the new fields still decodes") {
            val model =
                sysml2Model(name = "M") {
                    val a = partDef(name = "A")
                    bdd(name = "D") {
                        include(a)
                        comment(text = "hello \"w\"\n<b>", firstAnchor = a)
                    }
                }
            val json = Json { ignoreUnknownKeys = true }
            val text = json.encodeToString(Sysml2Model.serializer(), model)
            json.decodeFromString(Sysml2Model.serializer(), text) shouldBe model
            val old = """{"name":"Old","definitions":[],"usages":[],"diagrams":[{"type":"dev.kuml.sysml2.BdDiagram","name":"D"}]}"""
            val decoded = json.decodeFromString(Sysml2Model.serializer(), old)
            decoded.comments shouldBe emptyList()
            decoded.diagrams.single().noteIds shouldBe emptyList()
        }

        test("printer emits comments with explicit ids, escaped bodies and includeCommentById") {
            val model =
                sysml2Model(name = "M") {
                    val a = partDef(name = "A")
                    bdd(name = "D") {
                        include(a)
                        comment(text = "say \"hi\" \\ \$x\nline2", firstAnchor = a, id = "n1")
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "comment(\"say \\\"hi\\\" \\\\ \\\$x\\nline2\", \"A\", id = \"n1\")"
            dsl shouldContain "includeCommentById(\"n1\")"
        }

        test("note checker: unknown anchors, duplicate ids, not-shown warning") {
            val ok =
                sysml2Model(name = "M") {
                    partDef(name = "A")
                    comment(text = "x", "A")
                }
            Sysml2NoteChecker.check(ok) shouldBe emptyList()

            val orphan = sysml2Model(name = "M") { comment(text = "x") }
            Sysml2NoteChecker.check(orphan).map { it.code } shouldContainExactly listOf(Sysml2NoteChecker.NOT_SHOWN)

            // The builder now rejects colliding ids, so build the invalid model by copy.
            val dup = ok.copy(comments = ok.comments.map { it.copy(note = it.note.copy(id = "A")) })
            Sysml2NoteChecker.check(dup).any { it.code == Sysml2NoteChecker.DUPLICATE_ID } shouldBe true

            val bad =
                ok.copy(
                    commentLinks =
                        ok.commentLinks +
                            dev.kuml.sysml2.Sysml2CommentLink(
                                link =
                                    dev.kuml.core.model.note
                                        .KumlNoteLink(id = "l", noteId = "ghost", targetId = "A"),
                            ),
                )
            Sysml2NoteChecker.check(bad).any { it.code == Sysml2NoteChecker.ANCHOR_UNKNOWN } shouldBe true
        }

        test("notesVisibleIn: explicit noteIds or visible anchor; links to invisible targets dropped") {
            val model =
                sysml2Model(name = "M") {
                    partDef(name = "A")
                    partDef(name = "B")
                    comment(text = "both", "A", "B", id = "n")
                    comment(text = "free", id = "f")
                }
            val d = BdDiagram(name = "D", elementIds = listOf("A"), noteIds = listOf("f"))
            val vis = model.notesVisibleIn(diagram = d, visibleNodeIds = setOf("A"))
            vis.comments.map { it.id }.toSet() shouldBe setOf("n", "f")
            vis.links.map { it.link.targetId } shouldContainExactly listOf("A")
        }

        test("duplicate explicit comment ids are rejected by build") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    sysml2Model(name = "M") {
                        partDef(name = "A")
                        partDef(name = "B")
                        comment(text = "a", "A", id = "c")
                        comment(text = "b", "B", id = "c")
                    }
                }
            ex.message shouldContain "duplicate"
        }

        test("a comment id colliding with a definition id is rejected by build") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    sysml2Model(name = "M") {
                        partDef(name = "A")
                        comment(text = "a", "A", id = "A")
                    }
                }
            ex.message shouldContain "collides"
        }
    })
