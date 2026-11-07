package dev.kuml.io.latex

import dev.kuml.io.latex.note.NoteFixtures
import dev.kuml.io.latex.uml.UmlEdgeEndsFixtures
import dev.kuml.io.latex.uml.UmlEdgeLabelFixtures
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LatexSourceCheckTest :
    StringSpec({
        "a dangling message flow end is detected" {
            val tex = "\\node[x] (p_a) {};\n\\draw[flow] (p_a) -- node[midway] {M} (p_customer_start_1);"
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe setOf("p_customer_start_1")
        }

        "every other golden is clean" {
            val clean =
                mapOf(
                    "uml" to NoteFixtures.renderUml(),
                    "uml-standalone" to NoteFixtures.renderUml(standalone = true),
                    "bdd" to NoteFixtures.renderBdd(),
                    "stm" to NoteFixtures.renderStm(),
                    "c4" to NoteFixtures.renderC4(),
                    "c4-note" to NoteFixtures.renderC4(body = "x", kind = dev.kuml.core.model.note.NoteKind.NOTE),
                    "bpmn" to NoteFixtures.renderBpmnProcess(),
                    "bpmn-collab" to NoteFixtures.renderBpmnCollaboration(),
                    "journey" to NoteFixtures.renderBlueprint(),
                    "full" to NoteFixtures.renderBlueprint(full = true),
                    "labels" to UmlEdgeLabelFixtures.render(),
                    "ends-class" to UmlEdgeEndsFixtures.renderClass(),
                    "ends-uc" to UmlEdgeEndsFixtures.renderUseCase(),
                )
            for ((name, tex) in clean) {
                "$name: ${LatexSourceCheck.undefinedNodeReferences(tex)}" shouldBe "$name: []"
            }
        }

        "text, escaped braces and comments do not count" {
            val tex =
                """
                \node[x] (a) {Label (VIP) \{(x)\}};
                % (foo) in a comment
                \draw (a) -- (a.north east);
                """.trimIndent()
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
        }

        "an undefined reference is named" {
            LatexSourceCheck.undefinedNodeReferences("\\node (a) {};\n\\draw (a) -- (b.south);") shouldBe setOf("b")
        }
    })
