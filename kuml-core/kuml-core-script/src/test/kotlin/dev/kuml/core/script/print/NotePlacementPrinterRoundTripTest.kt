@file:Suppress("RequireNamedArguments")

package dev.kuml.core.script.print

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.InProcessScriptEvaluator
import dev.kuml.core.script.interpreter.InterpreterScriptEvaluator
import dev.kuml.uml.UmlComment
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.comment
import dev.kuml.uml.dsl.print.InterpreterUmlModelDslPrinter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** N2: the note placement hint survives DSL -> model -> printer -> interpreter -> model. */
class NotePlacementPrinterRoundTripTest :
    FunSpec({
        fun reparse(printed: String): KumlDiagram {
            val result = InterpreterScriptEvaluator.evaluate(source = printed, fileName = "note-placement.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result\n---\n$printed" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.Uml) { "expected a UML diagram, got: $extracted" }
            return extracted.diagram
        }

        fun placementsOf(d: KumlDiagram) = d.elements.filterIsInstance<UmlComment>().map { it.placement }

        test("every placement round-trips, NEAR included, with and without an anchor") {
            val original =
                classDiagram(name = "Placements") {
                    val a = classOf(name = "A")
                    for (p in NotePlacement.entries) {
                        comment(text = "free ${p.name}", placement = p)
                        comment(text = "anchored ${p.name}", firstAnchor = a, placement = p)
                    }
                }
            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "placement = NotePlacement.ABOVE"
            printed shouldContain "placement = NotePlacement.NEAR"
            placementsOf(reparse(printed)) shouldBe placementsOf(original)
        }

        test("a diagram without placements prints no placement argument") {
            val original =
                classDiagram(name = "Plain") {
                    val a = classOf(name = "A")
                    comment(text = "plain", firstAnchor = a)
                }
            InterpreterUmlModelDslPrinter.print(original) shouldNotContain "placement"
            InterpreterUmlModelDslPrinter.print(original) shouldNotContain "NotePlacement"
        }

        fun classScript(arg: String) =
            """
            classDiagram(name = "D") {
                val a = classOf(name = "A")
                comment(text = "n", a, $arg)
            }
            """.trimIndent()

        test("the interpreter rejects a wrong qualifier and an unknown value, naming the allowed ones") {
            val wrong = InterpreterScriptEvaluator.evaluate(source = classScript("placement = NoteKind.INFO"), fileName = "x.kuml.kts")
            (wrong is EvaluatedScript.Failure) shouldBe true
            (wrong as EvaluatedScript.Failure).message shouldContain "NotePlacement"
            val unknown =
                InterpreterScriptEvaluator.evaluate(
                    source = classScript("placement = NotePlacement.SIDEWAYS"),
                    fileName = "x.kuml.kts",
                )
            (unknown is EvaluatedScript.Failure) shouldBe true
            (unknown as EvaluatedScript.Failure).message shouldContain "ABOVE"
        }

        test("the Kotlin script host imports NotePlacement by default") {
            val result =
                InProcessScriptEvaluator.evaluate(
                    source =
                        """
                        sysml2Model("M") {
                            val v = partDef("V")
                            bdd("D") {
                                include(v)
                                comment("n", v, id = "n1", placement = NotePlacement.LEFT)
                            }
                        }
                        """.trimIndent(),
                    fileName = "p.kuml.kts",
                )
            require(result is EvaluatedScript.Success) { "evaluation failed: $result" }
            val ex = result.diagram
            require(ex is ExtractedDiagram.Sysml2)
            ex.model.comments
                .single()
                .note.placement shouldBe NotePlacement.LEFT
        }
    })
