package dev.kuml.io.latex.note

import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.blueprint.BlueprintLatexRenderer
import dev.kuml.io.latex.blueprint.BlueprintNoteTikz
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun notes(
    vararg targets: List<String>,
    kind: NoteKind = NoteKind.NOTE,
) = targets.mapIndexed { i, t -> Triple("Blueprint note $i", kind, t) }

class BlueprintNoteTikzTest :
    StringSpec({
        "a dog-ear note per kind with accent stripe and symbol" {
            for (kind in NoteKind.entries) {
                val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s1"), kind = kind))
                tex shouldContain "\\draw[kuml-note]"
                tex shouldContain "\\draw[kuml-note-fold]"
                if (kind.hasIcon) {
                    tex shouldContain "\\definecolor{kumlNoteTone"
                    tex shouldContain "even odd rule"
                    tex shouldContain "rectangle ("
                } else {
                    tex shouldNotContain "\\definecolor"
                }
            }
        }

        "the line and the dot sit on the east edge of the right stacked step" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s2b")))
            // s2b is the second step of the model's steps list that is not s1/s2a: index 2.
            tex shouldContain "\\node[kuml-bp-step] (kumlbpstep2) at"
            tex shouldContain
                "|- ([xshift=0.05cm,yshift=-0.65cm]kumlbpstep2.south east) " +
                "-- ([xshift=0.05cm,yshift=0cm]kumlbpstep2.east) -- (kumlbpstep2.east);"
            tex shouldContain "\\fill (kumlbpstep2.east) circle (2.000pt);"
        }

        "a box covered by a stacked box is anchored on its visible strip, with its own final vertical" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s2a"), listOf("s2b")), full = true)
            val lines = tex.lines().filter { it.contains("kuml-note-anchor]") }
            lines.size shouldBe 2
            val lower = lines.single { it.contains("kumlbpstep1.") } // Orders, below Pays
            val upper = lines.single { it.contains("kumlbpstep2.") }
            // Orders (lower) ends 0.2 cm above its south east corner, Pays (upper, on top) at its east anchor.
            lower shouldContain "-- ([yshift=0.20cm]kumlbpstep1.south east);"
            upper shouldContain "-- (kumlbpstep2.east);"
            tex shouldContain "\\fill ([yshift=0.20cm]kumlbpstep1.south east) circle (2.000pt);"
            // The final verticals sit at different x offsets right of the shared edge.
            val dx = lines.map { Regex("""xshift=([0-9.]+)cm""").find(it)!!.groupValues[1] }
            dx.toSet().size shouldBe 2
        }

        "the final vertical of a last-column step clears the label of the separator line" {
            val s6 = JourneyStep(id = "s6", name = "Rates", phaseRef = "p3", layer = BlueprintLayer.CUSTOMER_ACTIONS)
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s6")), full = true, extraSteps = listOf(s6))
            val line = tex.lines().single { it.contains("kuml-note-anchor]") }
            val dx = Regex("""xshift=([0-9.]+)cm""").find(line)!!.groupValues[1].toDouble()
            // Box edge at 10.55 cm, the label ends at the grid edge 10.8 cm: the run must lie right of it.
            (10.55 + dx > 10.8) shouldBe true
            // Other columns keep the small offset.
            NoteFixtures
                .renderBlueprint(notes = notes(listOf("s1")), full = true)
                .lines()
                .single { it.contains("kuml-note-anchor]") } shouldContain "xshift=0.05cm"
        }

        "lanes keep clearance to separator and boxes and a minimum distance, surplus links share lanes" {
            for (gap in listOf(0.06, 0.1, 0.2, 0.3)) {
                for (n in 1..8) {
                    val offs = (0 until n).map { BlueprintLatexRenderer.laneOffsetCm(gap = gap, lanes = n, lane = it) }
                    val clearance = BlueprintLatexRenderer.LANE_CLEARANCE_CM
                    val pitch = BlueprintLatexRenderer.LANE_PITCH_CM
                    offs.forEach { (it >= clearance - 1e-9 || n == 1 || gap < 0.11) shouldBe true }
                    offs.forEach { (it <= gap) shouldBe true }
                    val distinct = offs.map { Math.round(it * 1e6) }.toSet().sorted()
                    distinct.zipWithNext().forEach { (a, b) ->
                        ((b - a) / 1e6 >= pitch - 1e-6) shouldBe true
                    }
                }
            }
            // Three links in a 0.1 cm strip no longer become three lines 0.025 cm apart.
            BlueprintLatexRenderer.laneOffsetCm(gap = 0.1, lanes = 3, lane = 0) shouldBe
                BlueprintLatexRenderer.laneOffsetCm(gap = 0.1, lanes = 3, lane = 2)
        }

        "two links give two lines and two dots" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s2a", "s2b")))
            Regex("""kuml-note-anchor\] \(""").findAll(tex).count() shouldBe 2
            Regex("""circle \(2\.000pt\)""").findAll(tex).count() shouldBe 2
        }

        "no anchor, an unknown target or a step in a hidden layer still draw the note with a constant comment" {
            // The journey view hides the backstage and support layers.
            for (targets in listOf(emptyList(), listOf("nope"), listOf("s4", "s5"))) {
                val tex = NoteFixtures.renderBlueprint(notes = notes(targets))
                tex shouldContain "kuml-note-text"
                tex shouldNotContain "kuml-note-anchor]"
                if (targets.isNotEmpty()) tex shouldContain BlueprintNoteTikz.OMITTED_ANCHOR
            }
            NoteFixtures.renderBlueprint(notes = notes(listOf("s4")), full = true) shouldContain "kumlbpstep4.east"
        }

        "order: lines and dots before the notes, the notes in one scope at the end" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s1")), full = true)
            val step = tex.indexOf("\\node[kuml-bp-step]")
            val line = tex.indexOf("\\draw[kuml-note-anchor]")
            val dot = tex.indexOf("\\fill (kumlbpstep0.east)")
            val scope = tex.indexOf("\\begin{scope}[shift=")
            (step < line && line < dot && dot < scope) shouldBe true
            tex.indexOf("\\end{scope}") shouldBe tex.lastIndexOf("\\end{scope}")
        }

        "the line is routed in the gap below the step row, not straight across other step boxes" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s1")), full = true)
            val line = tex.lines().single { it.contains("kuml-note-anchor]") }
            line shouldContain "|- ([xshift=0.05cm,yshift=-0.45cm]kumlbpstep0.south east)"
            Regex("""\(-?[0-9.]+,-?[0-9.]+\) -- \(kumlbpstep0""").containsMatchIn(line) shouldBe false
        }

        "the line leaves the note column horizontally, so its vertical part is left of the notes" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s1")), full = true)
            val line = tex.lines().single { it.contains("kuml-note-anchor]") }
            val m = Regex("""\((-?[0-9.]+),(-?[0-9.]+)\) -- \((-?[0-9.]+),(-?[0-9.]+)\) \|-""").find(line)!!
            val (x0, y0, x1, y1) = m.destructured
            y1 shouldBe y0
            (x1.toDouble() < x0.toDouble()) shouldBe true
        }

        "several notes use distinct exit columns and distinct lanes, so no dashed segments coincide" {
            val tex =
                NoteFixtures.renderBlueprint(
                    notes = notes(listOf("s2a"), listOf("s2b"), listOf("s5"), listOf("s1")),
                    full = true,
                )
            val lines = tex.lines().filter { it.contains("kuml-note-anchor]") }
            lines.size shouldBe 4
            val exits =
                lines.map { Regex("""-- \((-?[0-9.]+),""").find(it)!!.groupValues[1] }
            exits.toSet().size shouldBe 4
            // s2a, s2b and s1 sit in the same band: their strips below the band must differ.
            val sameBand = lines.filter { l -> listOf("kumlbpstep1", "kumlbpstep2", "kumlbpstep0").any { l.contains("$it.south east") } }
            val dys = sameBand.map { Regex("""yshift=-([0-9.]+)cm""").find(it)!!.groupValues[1] }
            sameBand.size shouldBe 3
            dys.toSet().size shouldBe 3
        }

        "the route runs below the band, clear of the separator line and its label" {
            val tex = NoteFixtures.renderBlueprint(notes = notes(listOf("s2a")), full = true)
            val dy = Regex("""yshift=-([0-9.]+)cm""").find(tex)!!.groupValues[1].toDouble()
            // s2a is the lower box of a stack of two: its south edge is 0.1 cm above the band bottom.
            (dy > 0.1 + 0.05) shouldBe true
        }

        "the placement stacks downwards without overlap" {
            val placed =
                BlueprintNoteTikz.place(
                    (0 until 6).map {
                        dev.kuml.core.model.note
                            .KumlNote(id = "n$it", body = "Note $it")
                    },
                )
            placed.size shouldBe 6
            placed.zipWithNext().forEach { (a, b) -> (b.yPt >= a.yPt + a.size.height) shouldBe true }
        }

        "the height limit omits further notes with a comment" {
            val big = "word ".repeat(400)
            val tex = NoteFixtures.renderBlueprint(notes = (0 until 200).map { Triple(big, NoteKind.NOTE, emptyList()) })
            tex shouldContain BlueprintNoteTikz.OMITTED_NOTE
            val ys = Regex("""kumlbpnote\d+\) at \(0\.000pt, (-[0-9.]+)pt\)""").findAll(tex).map { it.groupValues[1].toFloat() }.toList()
            ys.all { it > -BlueprintNoteTikz.MAX_STACK_PT } shouldBe true
        }

        "without notes there are no names, styles or note output" {
            val tex = NoteFixtures.renderBlueprint()
            tex shouldNotContain "kumlbp"
            tex shouldNotContain "kuml-note"
        }

        "hostile note text stays inert" {
            for (body in HOSTILE_BODIES) {
                for (full in listOf(false, true)) {
                    assertTexSafe(
                        NoteFixtures.renderBlueprint(
                            notes = listOf(Triple(body, NoteKind.RISK, listOf("s1", "x\\input{y}\n%"))),
                            full = full,
                        ),
                    )
                }
            }
        }
    })
