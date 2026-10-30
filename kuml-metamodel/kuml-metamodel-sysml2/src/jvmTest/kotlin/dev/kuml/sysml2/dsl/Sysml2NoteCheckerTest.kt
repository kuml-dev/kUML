package dev.kuml.sysml2.dsl

import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.KumlNoteIssue
import dev.kuml.sysml2.constraint.NoteAnchorKind
import dev.kuml.sysml2.constraint.Sysml2NoteAnchors
import dev.kuml.sysml2.constraint.Sysml2NoteChecker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class Sysml2NoteCheckerTest :
    FunSpec(body = {

        test("W001: unknown anchor names anchor and note id, is a warning, and never throws") {
            val m = sysml2Model(name = "M") { comment(text = "SECRET BODY", "Gibtsnicht", id = "n") }
            val w = Sysml2NoteChecker.check(m).filter { it.code == KumlNoteCodes.ANCHOR_UNKNOWN }
            w shouldHaveSize 1
            w.single().severity shouldBe KumlNoteIssue.Severity.WARNING
            w.single().message shouldBe "Anker unbekannt: Gibtsnicht (Notiz n)"
            w.single().message shouldNotContain "SECRET"
        }

        test("anchor kinds: transition, port, connection, owner frame, partition, constraint") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "t", source = a, target = b)
                    val ts = partDef(name = "Teilsystem")
                    stateDef(name = "Idle")
                }
            Sysml2NoteAnchors.kindOf(model = m, id = "transition:A::B") shouldBe NoteAnchorKind.TRANSITION
            Sysml2NoteAnchors.kindOf(model = m, id = "A") shouldBe NoteAnchorKind.STATE
            Sysml2NoteAnchors.kindOf(model = m, id = "Teilsystem") shouldBe NoteAnchorKind.DEFINITION
            Sysml2NoteAnchors.kindOf(model = m, id = "Teilsystem", ownerId = "Teilsystem") shouldBe NoteAnchorKind.OWNER_FRAME
            Sysml2NoteAnchors.kindOf(model = m, id = "nope") shouldBe null
        }

        test("W002: a note listed in a BDD but anchored to a transition names element, anchor and diagram") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val t = transition(name = "t", source = a, target = b)
                    val blk = partDef(name = "Blk")
                    bdd(name = "Overview") {
                        include(blk)
                        comment(text = "x", firstAnchor = t, id = "n")
                    }
                }
            val diagram = m.diagrams.single()
            val w = Sysml2NoteChecker.check(model = m, diagram = diagram).single { it.code == KumlNoteCodes.ANCHOR_NOT_DRAWABLE }
            w.message shouldContain "transition:A::B"
            w.message shouldContain "Notiz n"
            w.message shouldContain "Diagramm Overview"
            Sysml2NoteChecker.check(m).none { it.code == KumlNoteCodes.ANCHOR_NOT_DRAWABLE } shouldBe true
        }

        test("W002 stays silent for a transition note in an STM and for notes visible only through another anchor") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val t = transition(name = "t", source = a, target = b)
                    stmDiagram(name = "S") {
                        include(a)
                        include(b)
                        comment(text = "x", firstAnchor = t, id = "n")
                    }
                }
            Sysml2NoteChecker.check(model = m, diagram = m.diagrams.single()).shouldBeEmpty()
        }

        test("W003: more than 8 anchors on one note") {
            val m =
                sysml2Model(name = "M") {
                    val defs = (1..9).map { partDef(name = "P$it") }
                    comment(text = "x", firstAnchor = defs.first(), *defs.drop(1).toTypedArray(), id = "many")
                }
            val w = Sysml2NoteChecker.check(m).single { it.code == KumlNoteCodes.LIMIT_APPLIED }
            w.subjectId shouldBe "many"
            w.message shouldContain "9"
        }
    })
