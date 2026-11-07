package dev.kuml.io.latex.note

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.bpmn.BpmnNoteTikz
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun notes(
    vararg targets: List<String>,
    kind: NoteKind = NoteKind.NOTE,
) = targets.mapIndexed { i, t -> Triple("Annotation number $i", kind, t) }

private fun floors(tex: String): List<Float> =
    Regex("""\(kumlnotea(\d+)\) at \(0pt,([0-9.]+)pt\)""").findAll(tex).map { it.groupValues[2].toFloat() }.toList()

class BpmnNoteTikzTest :
    StringSpec({
        "process: a bracket note per kind, no dog-ear and no accent stripe" {
            for (kind in NoteKind.entries) {
                val tex = NoteFixtures.renderBpmnProcess(notes = notes(listOf("t1"), kind = kind))
                tex shouldContain "\\draw[kuml-note-fold"
                tex shouldNotContain "\\draw[kuml-note]"
                tex shouldNotContain "rectangle (3.000pt"
                tex shouldContain "\\fill[white]"
                if (kind.hasIcon) {
                    tex shouldContain "\\definecolor{kumlNoteTone"
                    tex shouldContain "even odd rule"
                    tex shouldContain "kuml-note-fold, draw=kumlNoteTone"
                } else {
                    tex shouldNotContain "\\definecolor"
                }
            }
        }

        "process: the association joins the note anchor with the bare flow node name" {
            val tex = NoteFixtures.renderBpmnProcess(notes = notes(listOf("t1")))
            tex shouldContain "kuml-note-assoc/.style"
            tex shouldContain "\\draw[kuml-note-assoc] (kumlnotea0) -- (t1);"
            tex shouldNotContain "n_t1"
            tex shouldContain "\\coordinate (kumlnotebase) at (current bounding box.north);"
            tex shouldContain "shift={(t1.center |- kumlnotebase)}"
        }

        "process: several associations give several lines" {
            val tex = NoteFixtures.renderBpmnProcess(notes = notes(listOf("t1", "g1", "end1")))
            Regex("""kuml-note-assoc\] """).findAll(tex).count() shouldBe 3
        }

        "process: no anchor, unknown or undrawn target still draws the note, with a constant comment and no id" {
            for (targets in listOf(emptyList(), listOf("nope-123"), listOf("do1"))) {
                val tex = NoteFixtures.renderBpmnProcess(notes = notes(targets))
                tex shouldContain "kuml-note-text"
                tex shouldNotContain "kuml-note-assoc]"
                tex shouldNotContain "nope"
                tex shouldNotContain "do1"
                if (targets.isNotEmpty()) tex shouldContain BpmnNoteTikz.OMITTED_ANCHOR
            }
        }

        "process: floors rise strictly and never overlap" {
            val tex = NoteFixtures.renderBpmnProcess(notes = (0 until 6).map { Triple("Annotation $it", NoteKind.NOTE, listOf("t1")) })
            val ys = floors(tex)
            ys.size shouldBe 6
            val h = NoteLayout.sizeOf(body = "Annotation 0", frame = NoteFrameStyle.BRACKET).height
            ys.zipWithNext().forEach { (a, b) -> (b - a >= h) shouldBe true }
        }

        "process: lines come after the anchor coordinates and after the notes" {
            val tex = NoteFixtures.renderBpmnProcess(notes = notes(listOf("t1")))
            val coord = tex.indexOf("\\coordinate (kumlnotea0)")
            val line = tex.indexOf("\\draw[kuml-note-assoc]")
            val note = tex.indexOf("\\fill[white]")
            (coord in 0 until note) shouldBe true
            (note < line) shouldBe true
        }

        "process: a wide lower-floor note cannot hide the line of a higher floor" {
            val wide = "word ".repeat(40)
            val tex =
                NoteFixtures.renderBpmnProcess(
                    notes = listOf(Triple(wide, NoteKind.NOTE, listOf("t1")), Triple("Second", NoteKind.NOTE, listOf("g1"))),
                )
            val lastNote = tex.lastIndexOf("\\fill[white]")
            val g1Line = tex.indexOf("\\draw[kuml-note-assoc] (kumlnotea1) -- (g1);")
            (g1Line > lastNote) shouldBe true
        }

        "process: a flow node that starts like the note prefix moves the prefix" {
            val tex =
                NoteFixtures.renderBpmnProcess(
                    notes = notes(listOf("t1")),
                    flowIds = listOf("kumlnote0", "n_kumlnote1", "g1", "end1"),
                )
            tex shouldContain "(kumlnotexbase)"
            tex shouldNotContain "(kumlnotebase)"
        }

        "process: the height limit omits further notes with a comment" {
            val big = "word ".repeat(400)
            val tex = NoteFixtures.renderBpmnProcess(notes = (0 until 200).map { Triple(big, NoteKind.NOTE, listOf("t1")) })
            tex shouldContain BpmnNoteTikz.OMITTED_NOTE
            floors(tex).all { it < BpmnNoteTikz.MAX_STACK_PT } shouldBe true
        }

        "process: an empty picture uses the origin as base" {
            val tex = NoteFixtures.renderBpmnProcess(notes = notes(emptyList()), flowIds = emptyList())
            tex shouldContain "\\coordinate (kumlnotebase) at (0pt,0pt);"
            tex shouldNotContain "current bounding box"
        }

        "collaboration: notes below the pools, no lines, a constant comment per association" {
            val tex = NoteFixtures.renderBpmnCollaboration(notes = notes(listOf("tA"), emptyList()))
            tex shouldContain "\\coordinate (kumlnotebase) at (current bounding box.south west);"
            tex shouldContain BpmnNoteTikz.OMITTED_ANCHOR
            tex shouldNotContain "kuml-note-assoc]"
            tex shouldContain "kuml-note-text"
        }

        "collaboration: the height limit accounts for the pool offset" {
            // The fixture has two pools (180pt in total). Whatever the note height, the lowest drawn note edge stays above the pool-reduced
            // limit; without the pool term some of these heights would end between the reduced and the full limit.
            // two stacked pools of 80pt with a 20pt gap
            val limit = BpmnNoteTikz.MAX_STACK_PT - 180f
            for (words in listOf(60, 75, 90, 105, 120, 135, 150, 165, 180)) {
                val body = "word ".repeat(words)
                val tex = NoteFixtures.renderBpmnCollaboration(notes = (0 until 400).map { Triple(body, NoteKind.NOTE, emptyList()) })
                tex shouldContain BpmnNoteTikz.OMITTED_NOTE
                val lowest =
                    Regex("""\\fill\[white\] \(.*?, (-?[0-9.]+)pt\) rectangle \(.*?, (-?[0-9.]+)pt\)""")
                        .findAll(tex)
                        .map { it.groupValues[2].toFloat() }
                        .minOrNull()!!
                (-lowest <= limit + 1f) shouldBe true
            }
        }

        "collaboration: annotations of the referenced process are shown" {
            val (m, d) = NoteFixtures.bpmnCollaborationModel(notes = notes(emptyList()), processNotes = true)
            val tex =
                dev.kuml.io.latex.KumlLatexRenderer
                    .toLatex(model = m, diagram = d)
            tex shouldContain "Annotation number 0"
        }

        "collaboration: rows wrap at the maximum row width" {
            val tex = NoteFixtures.renderBpmnCollaboration(notes = (0 until 8).map { Triple("Annotation $it", NoteKind.NOTE, emptyList()) })
            val ys = Regex("""\(n_kumlnote\d+\) at \(([0-9.]+)pt, (-?[0-9.]+)pt\)""").findAll(tex).map { it.groupValues[2] }.toSet()
            (ys.size > 1) shouldBe true
        }

        "no notes: styles and note coordinates are absent" {
            val tex = NoteFixtures.renderBpmnProcess()
            tex shouldNotContain "kuml-note"
            tex shouldNotContain "kumlnote"
        }

        "hostile note text and ids stay inert" {
            for (body in HOSTILE_BODIES) {
                val tex =
                    NoteFixtures.renderBpmnProcess(
                        notes = listOf(Triple(body, NoteKind.WARNING, listOf("t\\input{x}\n1"))),
                        flowIds = listOf("s\"1", "t\\input{x}\n1", "g 1", "e%1"),
                        noteIdOf = { "a\\input{x}\n%" },
                    )
                assertTexSafe(tex)
                val coll = NoteFixtures.renderBpmnCollaboration(notes = listOf(Triple(body, NoteKind.INFO, listOf("tA"))))
                assertTexSafe(coll)
            }
        }

        "a pool name with a line break cannot leave its comment" {
            val tex = NoteFixtures.renderBpmnCollaboration(poolName = "Pool\n\\input{x}")
            tex shouldNotContain "\n\\input"
            tex.lines().none { it.trimStart().startsWith("\\input") } shouldBe true
        }
    })
