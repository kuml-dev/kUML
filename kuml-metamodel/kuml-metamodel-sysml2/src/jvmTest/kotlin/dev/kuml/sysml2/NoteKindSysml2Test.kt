@file:Suppress("RequireNamedArguments")

package dev.kuml.sysml2

import dev.kuml.core.model.note.NoteKind
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class NoteKindSysml2Test :
    FunSpec({
        test("the kind reaches the model from every comment verb, diagram-level and model-level") {
            val model =
                sysml2Model(name = "M") {
                    val v = partDef("V")
                    comment("a", v, kind = NoteKind.WARNING)
                    note("b", v, kind = NoteKind.RISK)
                    comment("c", kind = NoteKind.INFO)
                    comment("plain", v)
                    bdd("B") {
                        include(v)
                        comment("d", v, kind = NoteKind.DECISION)
                        note("e", kind = NoteKind.TODO)
                    }
                }
            model.comments.map { it.note.kind } shouldBe
                listOf(NoteKind.WARNING, NoteKind.RISK, NoteKind.INFO, NoteKind.NOTE, NoteKind.DECISION, NoteKind.TODO)
        }

        test("the printer writes the kind only for non-plain notes and the model prints identically without kinds") {
            val kinded =
                sysml2Model(name = "M") {
                    val v = partDef("V")
                    comment("a", v, kind = NoteKind.WARNING)
                    comment("b", v)
                }
            val printed = Sysml2DslPrinter.print(kinded)
            printed shouldContain "kind = NoteKind.WARNING"
            printed.lines().count { "NoteKind" in it } shouldBe 1

            val plain =
                sysml2Model(name = "M") {
                    val v = partDef("V")
                    comment("b", v)
                }
            Sysml2DslPrinter.print(plain) shouldNotContain "NoteKind"
        }
    })
