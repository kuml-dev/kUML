@file:Suppress("RequireNamedArguments")

package dev.kuml.sysml2.dsl.print

import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val NOTE_TEXT = "**b** *i* `c` [l](https://kuml.dev) \\* C:\\Users\n- a\n1. x\n1. y \$x"
private const val NOTE_PRINTED = "\"**b** *i* `c` [l](https://kuml.dev) \\\\* C:\\\\Users\\n- a\\n1. x\\n1. y \\\$x\""

/** Notes N3: SysML 2 note text with markup characters survives the printer and the model unchanged. */
class NoteMarkupPrintTest :
    FunSpec({
        test("the printer writes markup text as one escaped literal and the model keeps the text") {
            val model =
                sysml2Model(name = "M") {
                    val v = partDef("V")
                    comment(NOTE_TEXT, v)
                }
            model.comments
                .single()
                .note.body shouldBe NOTE_TEXT
            Sysml2DslPrinter.print(model) shouldContain NOTE_PRINTED
        }
    })
