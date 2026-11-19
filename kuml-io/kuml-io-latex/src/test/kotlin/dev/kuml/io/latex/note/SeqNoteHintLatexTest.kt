@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.note

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2CommentLink
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 118: the SysML 2 sequence LaTeX fallback (lifeline rectangles, no messages) evaluates a note's placement
 * hint through the shared placer and reports misses as W004.
 */
class SeqNoteHintLatexTest :
    StringSpec({
        val ids = listOf("user", "browser", "auth")

        fun model(
            placement: NotePlacement?,
            withNote: Boolean = true,
            anchor: String = "browser",
        ): Pair<Sysml2Model, SeqDiagram> {
            val lifelines = ids.map { LifelineDefinition(id = it, name = it) }
            val model =
                Sysml2Model(
                    name = "Seq",
                    definitions = lifelines,
                    comments =
                        if (withNote) {
                            listOf(
                                Sysml2Comment(
                                    note = KumlNote(id = "n1", body = "Hinted note", sourceLine = 5, placement = placement),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    commentLinks =
                        if (withNote) {
                            listOf(Sysml2CommentLink(link = KumlNoteLink(id = "note-link:n1::$anchor", noteId = "n1", targetId = anchor)))
                        } else {
                            emptyList()
                        },
                )
            val seq = SeqDiagram(name = "S", elementIds = ids, noteIds = if (withNote) listOf("n1") else emptyList())
            return model to seq
        }

        fun layout(extra: Map<NodeId, NodeLayout> = emptyMap()): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 600f, height = 440f),
                nodes =
                    mapOf(
                        NodeId("user") to NodeLayout(bounds = Rect(Point(20f, 220f), Size(140f, 160f))),
                        NodeId("browser") to NodeLayout(bounds = Rect(Point(180f, 220f), Size(140f, 160f))),
                        NodeId("auth") to NodeLayout(bounds = Rect(Point(340f, 220f), Size(140f, 160f))),
                    ) + extra,
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun render(
            placement: NotePlacement?,
            withNote: Boolean = true,
            lay: LayoutResult = layout(),
            anchor: String = "browser",
        ): String {
            val (m, d) = model(placement, withNote, anchor)
            return KumlLatexRenderer.toLatex(model = m, diagram = d, layoutResult = lay)
        }

        // North-west corner of a node `(name)`; TikZ y is the negated layout y (down is more negative), and the whole
        // scene is translated so its bounding box starts at x = 0, so notes are compared with the lifelines, not with
        // layout coordinates.
        fun pos(
            tex: String,
            name: String,
        ): Pair<Float, Float> {
            val m = Regex("""\($name\) at \((-?[0-9.]+)pt, (-?[0-9.]+)pt\)""").find(tex)
            checkNotNull(m) { "no node $name in:\n$tex" }
            return m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
        }

        "seq: the hinted note is drawn and the lifelines stay" {
            val (tex, misses) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE) }
            misses.shouldBeEmpty()
            tex shouldContain "Hinted note"
            tex shouldContain "browser"
        }

        "seq: ABOVE puts the note above the lifeline tops and reports nothing" {
            val (tex, misses) = NotePlacementMissCollector.collecting { render(NotePlacement.ABOVE) }
            misses.shouldBeEmpty()
            // the note's bottom edge (y - 32) lies above the lifeline tops
            (pos(tex, "n_n1").second - 32f >= pos(tex, "n_browser").second) shouldBe true
        }

        "seq: RIGHT puts the note right of an outermost anchor lifeline and reports nothing" {
            val (tex, misses) = NotePlacementMissCollector.collecting { render(NotePlacement.RIGHT, anchor = "auth") }
            misses.shouldBeEmpty()
            (pos(tex, "n_n1").first >= pos(tex, "n_auth").first + 140f) shouldBe true
        }

        "seq: LEFT puts the note left of an outermost anchor lifeline and reports nothing" {
            val (tex, misses) = NotePlacementMissCollector.collecting { render(NotePlacement.LEFT, anchor = "user") }
            misses.shouldBeEmpty()
            (pos(tex, "n_n1").first + 140f <= pos(tex, "n_user").first) shouldBe true
        }

        "seq: an impossible hint is reported with line and without the note body" {
            val roof = NodeLayout(bounds = Rect(Point(-2000f, -2000f), Size(5000f, 2000f + 220f)))
            val (_, misses) =
                NotePlacementMissCollector.collecting {
                    render(NotePlacement.ABOVE, lay = layout(extra = mapOf(NodeId("roof") to roof)))
                }
            misses shouldHaveSize 1
            val miss = misses.single()
            miss.wanted shouldBe NotePlacement.ABOVE
            miss.sourceLine shouldBe 5
            miss.message() shouldNotContain "Hinted"
        }

        "seq: no hint and NEAR give identical output and never warn" {
            val (none, noneMisses) = NotePlacementMissCollector.collecting { render(null) }
            val (near, nearMisses) = NotePlacementMissCollector.collecting { render(NotePlacement.NEAR) }
            noneMisses.shouldBeEmpty()
            nearMisses.shouldBeEmpty()
            near shouldBe none
        }

        "seq: a model without a note renders the lifelines only" {
            val tex = render(null, withNote = false)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldNotContain "Hinted"
        }
    })
