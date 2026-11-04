@file:Suppress("RequireNamedArguments")

package dev.kuml.core.script.print

import dev.kuml.c4.dsl.c4Model
import dev.kuml.c4.dsl.print.C4DslPrinter
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.InProcessScriptEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 6: notes survive C4 DSL -> model -> printer -> script -> model.
 *
 * C4 element ids are random and cannot be pinned through the DSL, so the comparison is on
 * normalized content: note id, body, kind, anchor element names, and the `noteIds` per diagram.
 */
class C4NotePrinterRoundTripTest :
    FunSpec({
        fun reparse(printed: String): C4Model {
            val result = InProcessScriptEvaluator.evaluate(source = printed, fileName = "c4-notes.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result\n---\n$printed" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.C4) { "expected a C4 diagram, got: $extracted" }
            return extracted.model
        }

        data class NoteView(
            val id: String,
            val body: String,
            val kind: NoteKind,
            val anchors: List<String>,
        )

        fun view(m: C4Model): Pair<List<NoteView>, List<List<String>>> {
            val names = m.elements.associate { it.id to it.name }
            val notes =
                m.notes.map { n ->
                    NoteView(
                        id = n.id,
                        body = n.body,
                        kind = n.kind,
                        anchors = m.noteLinks.filter { it.noteId == n.id }.map { names.getValue(it.targetId) },
                    )
                }
            return notes to m.diagrams.map { it.noteIds }
        }

        fun roundTrip(original: C4Model): String {
            val printed = C4DslPrinter.print(original)
            view(reparse(printed)) shouldBe view(original)
            return printed
        }

        test("every NoteKind round-trips, anchored and free-standing") {
            val original =
                c4Model(name = "Kinds") {
                    val a = person("A")
                    val s = softwareSystem("S")
                    for (k in NoteKind.entries) {
                        note("anchored ${k.name}", a, s, kind = k)
                    }
                    systemContextDiagram("Ctx") {
                        include(a, s)
                        for (k in NoteKind.entries) note("free ${k.name}", kind = k)
                    }
                }
            roundTrip(original)
        }

        test("mixed model-level and diagram notes keep registry order and noteIds") {
            val original =
                c4Model(name = "Mixed") {
                    val a = person("A")
                    val s = softwareSystem("S")
                    note("m1", a)
                    systemContextDiagram("D1") {
                        include(a, s)
                        note("d1-a")
                        note("d1-b", s)
                    }
                    note("m2", s)
                    systemLandscapeDiagram("D2") { note("d2-a", a) }
                }
            roundTrip(original)
        }

        test("a note on a nested container and on a component round-trips") {
            lateinit var web: C4Container
            lateinit var ctrl: dev.kuml.c4.model.C4Component
            val original =
                c4Model(name = "Nested") {
                    val sys =
                        softwareSystem("S") {
                            web = container("Web") { ctrl = component("Ctrl") }
                        }
                    note("on web", web, ctrl)
                    containerDiagram("Cd") { system = sys }
                }
            roundTrip(original)
        }

        test("markup and escape characters survive") {
            val texts =
                listOf(
                    "*bold* and `code` and [a](b) and <b>x</b>",
                    "back\\slash \"quoted\" \$x \${y} C:\\Users 0..* snake_case",
                    "line1\nline2\r\nline3\ttabbed",
                    "",
                    "Unicode: äöüß € 日本語 \uD83D\uDE00",
                )
            val original =
                c4Model(name = "Escapes") {
                    val a = person("A")
                    texts.forEach { note(it, a) }
                    systemContextDiagram("Ctx") { include(a) }
                }
            val printed = roundTrip(original)
            // "$" must be escaped in the printed text so it is never evaluated as a template.
            (printed.contains("\\\$x")) shouldBe true
        }

        test("a model without notes round-trips to a model without notes") {
            val original =
                c4Model(name = "None") {
                    val a = person("A")
                    systemContextDiagram("Ctx") { include(a) }
                }
            val m = reparse(C4DslPrinter.print(original))
            m.notes shouldBe emptyList()
            m.noteLinks shouldBe emptyList()
        }
    })
