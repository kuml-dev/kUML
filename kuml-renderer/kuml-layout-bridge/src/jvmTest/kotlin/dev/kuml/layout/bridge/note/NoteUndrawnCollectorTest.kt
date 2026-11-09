package dev.kuml.layout.bridge.note

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class NoteUndrawnCollectorTest :
    FunSpec(body = {
        val u = UndrawnNote(noteId = "n", anchorId = "a", reason = "nested", sourceLine = 7)

        test("report outside a collecting scope does nothing") {
            NoteUndrawnCollector.report(listOf(u))
            NoteUndrawnCollector.collecting { 1 }.second.shouldBeEmpty()
        }

        test("collecting returns the value and each note and anchor once") {
            val (value, undrawn) =
                NoteUndrawnCollector.collecting {
                    NoteUndrawnCollector.report(listOf(u))
                    NoteUndrawnCollector.report(listOf(u, u.copy(anchorId = "b")))
                    42
                }
            value shouldBe 42
            undrawn.map { it.anchorId } shouldBe listOf("a", "b")
        }

        test("the scope is restored, also after an exception, and nested scopes do not leak") {
            runCatching { NoteUndrawnCollector.collecting { error("boom") } }
            NoteUndrawnCollector.report(listOf(u))
            val (_, outer) =
                NoteUndrawnCollector.collecting {
                    NoteUndrawnCollector.collecting { NoteUndrawnCollector.report(listOf(u)) }.second.size shouldBe 1
                }
            outer.shouldBeEmpty()
        }

        test("another thread does not see the scope") {
            val (_, undrawn) =
                NoteUndrawnCollector.collecting {
                    val t = Thread { NoteUndrawnCollector.report(listOf(u)) }
                    t.start()
                    t.join()
                }
            undrawn.shouldBeEmpty()
        }

        test("the message names ids and the reason only") {
            u.message() shouldBe "note not drawn: n, anchor a (nested)"
        }
    })
