@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.chain
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.end
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.start
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.task
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val HOSTILE = "t_{1}\\input{x}#"

/** Three nodes in one pool (start, task, end) and one note per target; `targets` per note, by declaration order. */
private fun render(targets: List<List<String>>): String {
    val process = chain(id = "p1", name = "P", nodes = listOf(start("s1", "S"), task(HOSTILE, "T"), end("e1", "E")))
    val notes = targets.mapIndexed { i, _ -> BpmnTextAnnotation(note = KumlNote(id = "a$i", body = "Note number $i")) }
    val assocs =
        targets.flatMapIndexed { i, ts ->
            ts.mapIndexed { j, t -> BpmnAssociation(link = KumlNoteLink(id = "as$i-$j", noteId = "a$i", targetId = t)) }
        }
    val collab =
        BpmnCollaboration(
            id = "c1",
            name = "C",
            participants = listOf(BpmnParticipant(id = "pool1", name = "Pool", processRef = "p1")),
            textAnnotations = notes,
            associations = assocs,
        )
    val model =
        BpmnModel(
            name = "M",
            processes = listOf(process),
            collaborations = listOf(collab),
            diagrams = listOf(CollaborationDiagram(name = "D", collaborationId = "c1")),
        )
    return KumlLatexRenderer.toLatex(model = model)
}

private fun lines(tex: String) = Regex("""\\draw\[kuml-note-assoc\] \((kumlnotea\d+)\) -- \(([A-Za-z0-9_]+)\);""").findAll(tex).toList()

class BpmnCollaborationNoteAnchorTest :
    StringSpec({
        "one dotted line per association with a drawn target" {
            val tex = render(listOf(listOf("e1", "s1"), listOf("missing")))
            lines(tex).size shouldBe 2
            Regex(Regex.escape(BpmnNoteTikz.OMITTED_ANCHOR)).findAll(tex).count() shouldBe 1
        }

        "notes are ordered by the x position of their first drawn target" {
            // note 0 -> end (right), note 1 -> start (left), note 2 -> nowhere: emit order is 1, 0, 2
            val tex = render(listOf(listOf("e1"), listOf("s1"), emptyList()))
            val order = Regex("""\(n_kumlnote(\d+)\) at""").findAll(tex).map { it.groupValues[1] }.toList()
            order shouldBe listOf("1", "0", "2")
            lines(tex).map { it.groupValues[1] } shouldBe listOf("kumlnotea1", "kumlnotea0")
        }

        "the anchor sits at the top edge of the note" {
            val tex = render(listOf(listOf("s1")))
            tex shouldContain "\\coordinate (kumlnotea0) at (n_kumlnote0.north);"
        }

        "a hostile node id never reaches the output, only its sanitised name" {
            val tex = render(listOf(listOf(HOSTILE)))
            tex shouldNotContain "\\input{x}"
            lines(tex).size shouldBe 1
            lines(tex).single().groupValues[2] shouldBe BpmnLatexRenderer.sanitizeTikzId(HOSTILE)
        }
    })
