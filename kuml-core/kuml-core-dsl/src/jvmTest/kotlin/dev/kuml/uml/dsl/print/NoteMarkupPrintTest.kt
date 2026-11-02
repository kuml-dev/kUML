@file:Suppress("RequireNamedArguments")

package dev.kuml.uml.dsl.print

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlComment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

private const val NOTE_TEXT = "**b** *i* `c` [l](https://kuml.dev) \\* C:\\Users\n- a\n1. x\n1. y \$x"
private const val NOTE_PRINTED = "\"**b** *i* `c` [l](https://kuml.dev) \\\\* C:\\\\Users\\n- a\\n1. x\\n1. y \\\$x\""

/** Notes N3: note text with markup characters is written unchanged and only escaped as a Kotlin string literal. */
class NoteMarkupPrintTest :
    FunSpec({
        fun model(body: String): KumlModel =
            KumlModel(
                root = KumlDiagram(id = "D", name = "D", type = DiagramType.CLASS, elements = listOf(UmlComment(id = "c1", body = body))),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "D",
            )

        test("UmlModelDslPrinter writes markup text as one escaped literal") {
            UmlModelDslPrinter.print(model(NOTE_TEXT)) shouldContain "comment(text = $NOTE_PRINTED"
        }

        test("InterpreterUmlModelDslPrinter writes markup text as one escaped literal") {
            InterpreterUmlModelDslPrinter.print(model(NOTE_TEXT)) shouldContain "comment(text = $NOTE_PRINTED"
        }
    })
