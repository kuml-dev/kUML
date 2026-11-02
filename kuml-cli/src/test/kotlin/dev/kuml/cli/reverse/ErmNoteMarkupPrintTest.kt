@file:Suppress("RequireNamedArguments")

package dev.kuml.cli.reverse

import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val NOTE_TEXT = "**b** *i* `c` [l](https://kuml.dev) \\* C:\\Users\n- a\n1. x\n1. y \$x"
private const val NOTE_PRINTED = "\"**b** *i* `c` [l](https://kuml.dev) \\\\* C:\\\\Users\\n- a\\n1. x\\n1. y \${'$'}x\""

/** Notes N3: ERM note text with markup characters survives the printer and the model unchanged. */
class ErmNoteMarkupPrintTest :
    FunSpec({
        test("the printer writes markup text as one escaped literal and the model keeps the text") {
            val model =
                ermModel(name = "M") {
                    entity(name = "teams") { id(name = "id", type = ErmDataType.Integer(64)) }
                    note(NOTE_TEXT)
                }
            model.notes
                .single()
                .note.body shouldBe NOTE_TEXT
            ErmModelDslPrinter.print(model) shouldContain NOTE_PRINTED
        }
    })
