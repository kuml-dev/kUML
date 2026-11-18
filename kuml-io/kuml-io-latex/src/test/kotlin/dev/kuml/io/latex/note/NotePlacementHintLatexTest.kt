@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.note

import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.uml.UmlComment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 104: the LaTeX renderers evaluate the placement hint (Blueprint, C4) and report W004 misses. */
class NotePlacementHintLatexTest :
    StringSpec({
        fun blueprint(
            placement: NotePlacement?,
            target: String = "s1",
        ): BlueprintModel {
            val m = NoteFixtures.blueprintModel(notes = listOf(Triple("Hinted note", NoteKind.NOTE, listOf(target))))
            return m.copy(
                notes = m.notes.map { KumlNote(id = it.id, body = it.body, kind = it.kind, sourceLine = 9, placement = placement) },
            )
        }

        fun noteY(tex: String): Float = Regex("""kumlbpnote0\) at \(0\.000pt, (-?[0-9.]+)pt\)""").find(tex)!!.groupValues[1].toFloat()

        "blueprint: a BELOW hint moves the note below its anchor card and reports nothing" {
            val (tex, misses) = NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = blueprint(NotePlacement.BELOW)) }
            misses.shouldBeEmpty()
            // the default slot is the grid top (y = 0); BELOW sits at least a card below it
            (noteY(tex) < -50f) shouldBe true
        }

        "blueprint: RIGHT always holds and keeps the default slot" {
            val (tex, misses) = NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = blueprint(NotePlacement.RIGHT)) }
            misses.shouldBeEmpty()
            tex shouldBe KumlLatexRenderer.toLatex(model = blueprint(null))
        }

        "blueprint: LEFT cannot be honoured and is reported with placed RIGHT, without the note body" {
            val (_, misses) = NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = blueprint(NotePlacement.LEFT)) }
            misses shouldHaveSize 1
            val miss = misses.single()
            miss.noteId shouldBe "n0"
            miss.wanted shouldBe NotePlacement.LEFT
            miss.placed shouldBe NotePlacement.RIGHT
            miss.sourceLine shouldBe 9
            miss.message() shouldNotContain "Hinted"
        }

        "blueprint: ABOVE the top card cannot be honoured and is reported" {
            val (_, misses) = NotePlacementMissCollector.collecting { KumlLatexRenderer.toLatex(model = blueprint(NotePlacement.ABOVE)) }
            misses.map { it.wanted } shouldBe listOf(NotePlacement.ABOVE)
        }

        "blueprint: no hint and NEAR give byte-identical output and never warn" {
            val (near, nearMisses) =
                NotePlacementMissCollector.collecting {
                    KumlLatexRenderer.toLatex(
                        model = blueprint(NotePlacement.NEAR),
                    )
                }
            near shouldBe KumlLatexRenderer.toLatex(model = blueprint(null))
            nearMisses.shouldBeEmpty()
        }

        fun c4(
            placement: NotePlacement?,
            blocked: Boolean,
        ): String {
            val (m, d, l) = NoteFixtures.c4(body = "Hinted note")
            val model =
                m.copy(
                    notes = m.notes.map { KumlNote(id = it.id, body = it.body, kind = it.kind, sourceLine = 4, placement = placement) },
                )
            val layout =
                if (blocked) {
                    val roof = Rect(origin = Point(-2000f, -2000f), size = Size(width = 5000f, height = 2000f + 20f))
                    l.copy(nodes = l.nodes + (NodeId("roof") to NodeLayout(bounds = roof)))
                } else {
                    l
                }
            return KumlLatexRenderer.toLatex(diagram = d, model = model, layoutResult = layout)
        }

        "c4: a honoured hint reports nothing, an impossible one reaches the collector in the latex path" {
            val (_, ok) = NotePlacementMissCollector.collecting { c4(NotePlacement.BELOW, blocked = false) }
            ok.shouldBeEmpty()
            val (_, miss) = NotePlacementMissCollector.collecting { c4(NotePlacement.ABOVE, blocked = true) }
            miss shouldHaveSize 1
            miss.single().wanted shouldBe NotePlacement.ABOVE
        }

        val roof = NodeLayout(bounds = Rect(origin = Point(-2000f, -2000f), size = Size(width = 5000f, height = 2000f + 20f)))

        "uml: a honoured ABOVE hint reports nothing, a blocked one reaches the collector in the latex path" {
            fun render(
                placement: NotePlacement?,
                blocked: Boolean,
            ): String {
                val (d, l) = NoteFixtures.uml(body = "Hinted note")
                val diagram =
                    d.copy(elements = d.elements.map { if (it is UmlComment) it.copy(placement = placement) else it })
                val layout = if (blocked) l.copy(nodes = l.nodes + (NodeId("roof") to roof)) else l
                return KumlLatexRenderer.toLatex(diagram = diagram, layoutResult = layout)
            }
            val (_, ok) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE, blocked = false) }
            ok.shouldBeEmpty()
            val (_, miss) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE, blocked = true) }
            miss shouldHaveSize 1
            miss.single().wanted shouldBe NotePlacement.ABOVE
            val (_, none) = NotePlacementMissCollector.collecting { render(null, blocked = true) }
            none.shouldBeEmpty()
        }

        "sysml2: a honoured ABOVE hint reports nothing, a blocked one reaches the collector in the latex path" {
            fun render(
                placement: NotePlacement?,
                blocked: Boolean,
            ): String {
                val (m, d, l) = NoteFixtures.bddModel(body = "Hinted note")
                val model =
                    m.copy(
                        comments =
                            m.comments.map {
                                Sysml2Comment(
                                    note =
                                        KumlNote(
                                            id = it.note.id,
                                            body = it.note.body,
                                            kind = it.note.kind,
                                            sourceLine = 3,
                                            placement = placement,
                                        ),
                                )
                            },
                    )
                val layout = if (blocked) l.copy(nodes = l.nodes + (NodeId("roof") to roof)) else l
                return KumlLatexRenderer.toLatex(model = model, diagram = d, layoutResult = layout)
            }
            val (_, ok) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE, blocked = false) }
            ok.shouldBeEmpty()
            val (_, miss) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE, blocked = true) }
            miss shouldHaveSize 1
            miss.single().wanted shouldBe NotePlacement.ABOVE
            miss.single().sourceLine shouldBe 3
        }
    })
