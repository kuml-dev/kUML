@file:Suppress("RequireNamedArguments")

package dev.kuml.core.script.print

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.interpreter.InterpreterScriptEvaluator
import dev.kuml.uml.UmlComment
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.comment
import dev.kuml.uml.dsl.print.InterpreterUmlModelDslPrinter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** Notes N1: the note kind survives DSL -> model -> printer -> interpreter -> model. */
class NoteKindPrinterRoundTripTest :
    FunSpec({
        fun reparse(printed: String): KumlDiagram {
            val result = InterpreterScriptEvaluator.evaluate(source = printed, fileName = "note-kind.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result\n---\n$printed" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.Uml) { "expected a UML diagram, got: $extracted" }
            return extracted.diagram
        }

        fun kindsOf(d: KumlDiagram) = d.elements.filterIsInstance<UmlComment>().map { it.kind }

        test("every kind round-trips, with and without an anchor") {
            val original =
                classDiagram(name = "Kinds") {
                    val a = classOf(name = "A")
                    for (kind in NoteKind.entries) {
                        comment(text = "free ${kind.name}", kind = kind)
                        comment(text = "anchored ${kind.name}", firstAnchor = a, kind = kind)
                    }
                }
            val printed = InterpreterUmlModelDslPrinter.print(original)
            kindsOf(reparse(printed)) shouldBe kindsOf(original)
        }

        test("a diagram without kinds prints exactly as before: no kind argument") {
            val original =
                classDiagram(name = "Plain") {
                    val a = classOf(name = "A")
                    comment(text = "plain", firstAnchor = a)
                }
            InterpreterUmlModelDslPrinter.print(original) shouldNotContain "kind"
            InterpreterUmlModelDslPrinter.print(original) shouldNotContain "NoteKind"
        }
    })
