package dev.kuml.io.latex.note

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.bpmn.BpmnNoteTikz
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 105: the LaTeX BPMN renderer honours the note placement hint (process: outside the flow; collaboration: SVG rule). */
class BpmnNoteHintTikzTest :
    StringSpec({
        fun BpmnTextAnnotation.with(
            placement: NotePlacement?,
            line: Int? = 7,
        ) = copy(note = KumlNote(id = note.id, body = note.body, kind = note.kind, sourceLine = line, placement = placement))

        fun process(
            placement: NotePlacement?,
            targets: List<String> = listOf("t1"),
            count: Int = 1,
            body: String = "Hinted body",
        ): Pair<String, List<PlacementHintMiss>> {
            val (m, d) = NoteFixtures.bpmnProcessModel(notes = (0 until count).map { Triple(body, NoteKind.NOTE, targets) })
            val model = m.copy(processes = m.processes.map { p -> p.copy(textAnnotations = p.textAnnotations.map { it.with(placement) }) })
            return NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = model, diagram = d) }
        }

        fun collaboration(
            placement: NotePlacement?,
            targets: List<String> = listOf("tA"),
        ): Pair<String, List<PlacementHintMiss>> {
            val (m, d) = NoteFixtures.bpmnCollaborationModel(notes = listOf(Triple("Hinted body", NoteKind.NOTE, targets)))
            val model: BpmnModel =
                m.copy(
                    collaborations =
                        m.collaborations.map { c -> c.copy(textAnnotations = c.textAnnotations.map { it.with(placement) }) },
                )
            return NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = model, diagram = d) }
        }

        fun anchor(tex: String): Pair<Float, Float> {
            val m = Regex("""\(kumlnotea0\) at \(([-0-9.]+)pt,([-0-9.]+)pt\)""").find(tex)!!
            return m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
        }

        "process: ABOVE, NEAR and no hint give byte-identical output and no miss" {
            val (none, noneMisses) = process(null)
            val (above, aboveMisses) = process(NotePlacement.ABOVE)
            val (near, nearMisses) = process(NotePlacement.NEAR)
            above shouldBe none
            near shouldBe none
            listOf(noneMisses, aboveMisses, nearMisses).forEach { it.shouldBeEmpty() }
            none shouldNotContain "kumlnotebs"
        }

        "process: BELOW puts the note under the flow, descending stack, no miss" {
            val (tex, misses) = process(NotePlacement.BELOW, count = 3)
            misses.shouldBeEmpty()
            tex shouldContain "\\coordinate (kumlnotebs) at (current bounding box.south);"
            tex shouldContain "shift={(t1.center |- kumlnotebs)}"
            val ys = Regex("""\(kumlnotea\d\) at \(0pt,(-[0-9.]+)pt\)""").findAll(tex).map { it.groupValues[1].toFloat() }.toList()
            ys shouldHaveSize 3
            ys.zipWithNext().all { (a, b) -> b < a } shouldBe true
            ys.first() shouldBe -BpmnNoteTikz.NOTE_BASE_GAP
            tex shouldContain "\\draw[kuml-note-assoc] (kumlnotea0) -- (t1);"
        }

        "process: RIGHT and LEFT put the notes in columns beside the flow" {
            val (right, rm) = process(NotePlacement.RIGHT, count = 2)
            rm.shouldBeEmpty()
            right shouldContain "\\coordinate (kumlnotebe) at (current bounding box.east);"
            right shouldContain "shift={(kumlnotebe |- t1.center)}"
            val xr = Regex("""\(kumlnotea\d\) at \(([-0-9.]+)pt,0\.000pt\)""").findAll(right).map { it.groupValues[1].toFloat() }.toList()
            (xr.size == 2 && xr[0] > 0f && xr[1] > xr[0]) shouldBe true
            val (left, lm) = process(NotePlacement.LEFT)
            lm.shouldBeEmpty()
            left shouldContain "\\coordinate (kumlnotebw) at (current bounding box.west);"
            (anchor(left).first < 0f) shouldBe true
        }

        "process: a side hint without a drawn anchor stays in the default tier and is reported once, without the body" {
            for (side in listOf(NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (tex, misses) = process(side, targets = emptyList())
                misses shouldHaveSize 1
                val miss = misses.single()
                miss.noteId shouldBe "a0"
                miss.wanted shouldBe side
                miss.placed shouldBe NotePlacement.NEAR
                miss.sourceLine shouldBe 7
                miss.message() shouldNotContain "Hinted"
                tex shouldNotContain "kumlnotebs"
            }
        }

        "process: a stack cut by the size limit is reported" {
            val big = "word ".repeat(400)
            val (tex, misses) = process(NotePlacement.BELOW, count = 200, body = big)
            tex shouldContain BpmnNoteTikz.OMITTED_NOTE
            (misses.isNotEmpty()) shouldBe true
            misses.all { it.wanted == NotePlacement.BELOW } shouldBe true
        }

        "collaboration: no hint and NEAR give identical output" {
            val (none, a) = collaboration(null)
            val (near, b) = collaboration(NotePlacement.NEAR)
            near shouldBe none
            a.shouldBeEmpty()
            b.shouldBeEmpty()
        }

        "collaboration: ABOVE goes above the pool block, BELOW below the anchor, each with a line to the target" {
            val (above, am) = collaboration(NotePlacement.ABOVE)
            am.shouldBeEmpty()
            val ay = Regex("""\(n_kumlnote0\) at \(([-0-9.]+)pt, ([-0-9.]+)pt\)""").find(above)!!.groupValues[2].toFloat()
            (ay > 0f) shouldBe true
            above shouldContain "\\draw[kuml-note-assoc] (kumlnotea0) -- (tA);"
            val (below, bm) = collaboration(NotePlacement.BELOW)
            bm.shouldBeEmpty()
            val by = Regex("""\(n_kumlnote0\) at \(([-0-9.]+)pt, ([-0-9.]+)pt\)""").find(below)!!.groupValues[2].toFloat()
            (by < -40f) shouldBe true
            below shouldContain "\\draw[kuml-note-assoc] (kumlnotea0) -- (tA);"
        }

        "collaboration: LEFT and RIGHT are placed (outside the pools or in the lane) and reported at most once" {
            for (side in listOf(NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (tex, misses) = collaboration(side)
                (misses.size <= 1) shouldBe true
                misses.forEach { it.sourceLine shouldBe 7 }
                tex shouldContain "(n_kumlnote0)"
                tex shouldNotContain "Hinted body\u0000"
            }
        }

        "collaboration: a hinted note whose target is not drawn keeps the default row and is not reported" {
            val (tex, misses) = collaboration(NotePlacement.ABOVE, targets = listOf("nope-123"))
            misses.shouldBeEmpty()
            tex shouldContain "kumlnotebase"
            tex shouldContain BpmnNoteTikz.OMITTED_ANCHOR
        }
    })
