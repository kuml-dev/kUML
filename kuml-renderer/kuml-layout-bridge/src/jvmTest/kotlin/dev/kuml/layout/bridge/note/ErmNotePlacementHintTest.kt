@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNote
import dev.kuml.erm.model.ErmNoteLink
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private val item = rect(x = 400f, y = 300f, w = 120f, h = 60f)
private val elkNote = rect(x = 600f, y = 300f, w = 100f, h = 40f)

private fun layoutOf(
    anchorNode: String = "a",
    extra: Map<String, Rect> = emptyMap(),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = Size(width = 900f, height = 700f),
    nodes =
        (mapOf(anchorNode to item, "n1" to elkNote) + extra).entries.associate { (k, v) ->
            NodeId(k) to NodeLayout(bounds = v)
        },
    edges = mapOf(EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 600f, y = 320f), target = Point(x = 520f, y = 330f))),
    groups = emptyMap(),
)

private fun modelWith(
    placement: NotePlacement?,
    target: String = "a",
) = ErmModel(
    name = "Shop",
    entities = listOf(ErmEntity(id = "a", name = "Item")),
    notes = listOf(ErmNote(note = KumlNote(id = "n1", body = "Note text", placement = placement, sourceLine = 7))),
    noteLinks = listOf(ErmNoteLink(link = KumlNoteLink(id = "l1", noteId = "n1", targetId = target))),
)

class ErmNotePlacementHintTest :
    FunSpec({
        fun run(
            placement: NotePlacement?,
            layout: LayoutResult = layoutOf(),
            model: ErmModel = modelWith(placement),
            resolveTarget: (String) -> String = { it },
        ) = NotePlacementMissCollector.collecting {
            ErmNotePlacement.place(model = model, layout = layout, resolveTarget = resolveTarget)
        }

        test("each side hint puts the note on that side of its anchor and reports nothing") {
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (res, misses) = run(side)
                val n =
                    res.layout.nodes
                        .getValue(NodeId("n1"))
                        .bounds
                misses.shouldBeEmpty()
                when (side) {
                    NotePlacement.ABOVE -> (n.bottom <= item.top + 0.5f) shouldBe true
                    NotePlacement.BELOW -> (n.top >= item.bottom - 0.5f) shouldBe true
                    NotePlacement.LEFT -> (n.right <= item.left + 0.5f) shouldBe true
                    else -> (n.left >= item.right - 0.5f) shouldBe true
                }
                (EdgeId("l1") in res.layout.edges) shouldBe true
            }
        }

        test("no hint and NEAR leave the ELK layout untouched, without any miss") {
            val layout = layoutOf()
            val (none, noneMisses) = run(null, layout)
            val (near, nearMisses) = run(NotePlacement.NEAR, layout)
            none.layout shouldBe layout
            near.layout shouldBe layout
            noneMisses.shouldBeEmpty()
            nearMisses.shouldBeEmpty()
        }

        test("a note with an unresolvable anchor keeps the ELK position and reports nothing") {
            val layout = layoutOf()
            val (res, misses) = run(NotePlacement.ABOVE, layout, modelWith(NotePlacement.ABOVE, target = "missing"))
            res.layout shouldBe layout
            misses.shouldBeEmpty()
        }

        test("an impossible side is reported once as W004 with the source line and falls back to the default placement") {
            val blocked = layoutOf(extra = mapOf("roof" to rect(-400f, -400f, 1800f, 676f)))
            val (res, misses) = run(NotePlacement.ABOVE, blocked)
            misses shouldHaveSize 1
            misses.single().noteId shouldBe "n1"
            misses.single().wanted shouldBe NotePlacement.ABOVE
            misses.single().sourceLine shouldBe 7
            res.hintNotHonoured shouldHaveSize 1
            (NodeId("n1") in res.layout.nodes) shouldBe true
        }

        test("two runs give equal results") {
            run(NotePlacement.LEFT).first shouldBe run(NotePlacement.LEFT).first
        }

        test("Chen: the link target is mapped to the prefixed entity node and attribute ellipses are obstacles") {
            val chenId = ErmChenLayoutBridge.ENTITY_PREFIX + "a"
            val attr = rect(x = 440f, y = 200f, w = 90f, h = 50f)
            val layout = layoutOf(anchorNode = chenId, extra = mapOf(ErmChenLayoutBridge.ATTR_PREFIX + "a::id" to attr))
            val (res, misses) =
                run(NotePlacement.ABOVE, layout, resolveTarget = ErmNotePlacement.chenTarget(layout = layout))
            misses.shouldBeEmpty()
            val n =
                res.layout.nodes
                    .getValue(NodeId("n1"))
                    .bounds
            (n.bottom <= item.top + 0.5f) shouldBe true
            // never on top of the attribute ellipse
            val overlaps = n.left < attr.right && n.right > attr.left && n.top < attr.bottom && n.bottom > attr.top
            overlaps shouldBe false
        }

        test("Chen: without the target mapping the plain id does not resolve and the layout is untouched") {
            val chenId = ErmChenLayoutBridge.ENTITY_PREFIX + "a"
            val layout = layoutOf(anchorNode = chenId)
            run(NotePlacement.ABOVE, layout).first.layout shouldBe layout
        }

        // Nacharbeit 120: a hinted note avoids the name label at the centre of a relationship edge (vertical at x = 700).
        val relName = "is placed by the customer"

        fun withRelationship(name: String?): Pair<LayoutResult, ErmModel> {
            val base = layoutOf()
            val layout =
                base.copy(
                    edges =
                        base.edges +
                            (EdgeId("r1") to EdgeRoute.Direct(source = Point(x = 700f, y = 250f), target = Point(x = 700f, y = 410f))),
                )
            val model =
                modelWith(NotePlacement.RIGHT).let {
                    it.copy(
                        relationships =
                            listOf(
                                ErmRelationship(
                                    id = "r1",
                                    name = name,
                                    sourceEntityId = "a",
                                    targetEntityId = "a",
                                    sourceCardinality = Cardinality.ONE,
                                    targetCardinality = Cardinality.ZERO_MANY,
                                ),
                            ),
                    )
                }
            return layout to model
        }

        fun noteOf(res: NotePlacementResult) =
            res.layout.nodes
                .getValue(NodeId("n1"))
                .bounds

        fun labelRect(): Rect {
            val size = Sysml2LabelSizing.sizeOf(listOf(relName))
            return rect(x = 700f - size.width / 2f, y = 330f - size.height / 2f, w = size.width, h = size.height)
        }

        fun Rect.hits(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

        test("a hinted note avoids the relationship name label") {
            val (layout, model) = withRelationship(relName)
            val (res, misses) = run(NotePlacement.RIGHT, layout, model)
            val n = noteOf(res)
            n.hits(labelRect()) shouldBe false
            (misses.size <= 1) shouldBe true
            if (misses.isEmpty()) (n.left >= item.right - 0.5f) shouldBe true
        }

        test("the unlabelled position collides with the label, so the test above is meaningful") {
            noteOf(run(NotePlacement.RIGHT).first).hits(labelRect()) shouldBe true
        }

        test("without a relationship name the hint is honoured exactly as without the relationship") {
            val (layout, model) = withRelationship(null)
            val (res, misses) = run(NotePlacement.RIGHT, layout, model)
            misses.shouldBeEmpty()
            noteOf(res) shouldBe noteOf(run(NotePlacement.RIGHT).first)
        }

        test("an unhinted note keeps the layout untouched next to a labelled relationship") {
            val (layout, model) = withRelationship(relName)
            run(
                null,
                layout,
                model.copy(notes = listOf(ErmNote(note = KumlNote(id = "n1", body = "Note text", placement = null)))),
            ).first.layout shouldBe
                layout
        }
    })
