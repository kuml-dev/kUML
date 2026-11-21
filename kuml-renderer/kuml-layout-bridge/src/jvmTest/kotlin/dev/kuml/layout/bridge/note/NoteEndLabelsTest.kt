@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
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
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private val anchor = r(x = 400f, y = 300f, w = 120f, h = 60f)
private val elkNote = r(x = 600f, y = 300f, w = 100f, h = 40f)

private fun layoutOf() =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(width = 900f, height = 700f),
        nodes = mapOf("a" to anchor, "n1" to elkNote).entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
        edges =
            mapOf(
                EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 600f, y = 320f), target = Point(x = 520f, y = 330f)),
                EdgeId("e1") to EdgeRoute.Direct(source = Point(x = 460f, y = 300f), target = Point(x = 460f, y = 100f)),
            ),
        groups = emptyMap(),
    )

private fun Rect.hits(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

private fun NotePlacementResult.note() =
    layout.nodes
        .getValue(NodeId("n1"))
        .bounds

/** Nacharbeit 124: notes keep clear of the labels at the edge ends (multiplicity, role, cardinality glyph). */
class NoteEndLabelsTest :
    FunSpec({
        // A label rectangle right beside the anchor on the right, where a RIGHT-hinted note would otherwise go.
        val endLabel = r(x = 522f, y = 312f, w = 90f, h = 36f)
        val endLabels = mapOf("e1" to listOf(endLabel))

        val umlDiagram =
            KumlDiagram(
                id = "d",
                name = "D",
                type = DiagramType.CLASS,
                elements =
                    listOf(
                        UmlClass(id = "a", name = "A"),
                        UmlComment(id = "n1", body = "Note text", placement = NotePlacement.RIGHT),
                        UmlCommentLink(id = "l1", commentId = "n1", annotatedElementId = "a"),
                    ),
            )

        fun umlRun(
            endLabelRects: (LayoutResult) -> Map<String, List<Rect>> = { emptyMap() },
            layout: LayoutResult = layoutOf(),
            diagram: KumlDiagram = umlDiagram,
        ) = NotePlacementMissCollector.collecting {
            UmlNotePlacement.place(diagram = diagram, layout = layout, endLabelRects = endLabelRects)
        }

        val ermModel =
            ErmModel(
                name = "Shop",
                entities = listOf(ErmEntity(id = "a", name = "Item")),
                relationships =
                    listOf(
                        ErmRelationship(
                            id = "e1",
                            name = null,
                            sourceEntityId = "a",
                            targetEntityId = "a",
                            sourceCardinality = Cardinality.ONE,
                            targetCardinality = Cardinality.ZERO_MANY,
                        ),
                    ),
                notes = listOf(ErmNote(note = KumlNote(id = "n1", body = "Note text", placement = NotePlacement.RIGHT, sourceLine = 3))),
                noteLinks = listOf(ErmNoteLink(link = KumlNoteLink(id = "l1", noteId = "n1", targetId = "a"))),
            )

        fun ermRun(endLabelRects: (LayoutResult) -> Map<String, List<Rect>> = { emptyMap() }) =
            NotePlacementMissCollector.collecting {
                ErmNotePlacement.place(model = ermModel, layout = layoutOf(), endLabelRects = endLabelRects)
            }

        test("UML: the note keeps clear of a multiplicity or role label on its wanted side (guarded against a vacuous test)") {
            umlRun().first.note().hits(endLabel) shouldBe true
            val (res, misses) = umlRun(endLabelRects = { endLabels })
            res.note().hits(endLabel) shouldBe false
            (misses.size <= 1) shouldBe true
        }

        test("UML: several end labels (multiplicity and role) are all avoided") {
            val second = r(x = 522f, y = 352f, w = 90f, h = 26f)
            val (res, _) = umlRun(endLabelRects = { mapOf("e1" to listOf(endLabel, second)) })
            res.note().hits(endLabel) shouldBe false
            res.note().hits(second) shouldBe false
        }

        test("UML: the callback receives the layout without the taken note and its link") {
            var seen: LayoutResult? = null
            umlRun(endLabelRects = { l ->
                seen = l
                emptyMap()
            })
            (NodeId("n1") in seen!!.nodes) shouldBe false
            (EdgeId("l1") in seen!!.edges) shouldBe false
            (EdgeId("e1") in seen!!.edges) shouldBe true
        }

        test("UML: end labels on another side leave the note exactly where it was without them") {
            val elsewhere = r(x = 400f, y = 100f, w = 60f, h = 30f)
            umlRun(endLabelRects = { mapOf("e1" to listOf(elsewhere)) }).first.note() shouldBe umlRun().first.note()
        }

        test("UML: a side blocked entirely by end labels falls back and is reported as W004, never dropped silently") {
            val wall = r(x = 522f, y = -400f, w = 1500f, h = 1500f)
            val (res, misses) = umlRun(endLabelRects = { mapOf("e1" to listOf(wall)) })
            misses shouldHaveSize 1
            misses.single().wanted shouldBe NotePlacement.RIGHT
            res.hintNotHonoured shouldHaveSize 1
            (NodeId("n1") in res.layout.nodes) shouldBe true
        }

        test("UML: without a hinted note the callback is never invoked and the layout is returned as given") {
            var calls = 0
            val layout = layoutOf()
            val plain = umlDiagram.copy(elements = umlDiagram.elements.map { if (it is UmlComment) it.copy(placement = null) else it })
            val (res, misses) =
                umlRun(
                    endLabelRects = {
                        calls++
                        emptyMap()
                    },
                    layout = layout,
                    diagram = plain,
                )
            calls shouldBe 0
            (res.layout === layout) shouldBe true
            misses.shouldBeEmpty()
        }

        test("ERM: a role or glyph rectangle on the wanted side is avoided, also for a relationship without name or role") {
            ermRun().first.note().hits(endLabel) shouldBe true
            val (res, misses) = ermRun(endLabelRects = { endLabels })
            res.note().hits(endLabel) shouldBe false
            (misses.size <= 1) shouldBe true
        }

        test("ERM: end labels on another side do not move the note") {
            val elsewhere = r(x = 400f, y = 100f, w = 60f, h = 30f)
            ermRun(endLabelRects = { mapOf("e1" to listOf(elsewhere)) }).first.note() shouldBe ermRun().first.note()
        }

        test("ERM: a blocked side is reported as W004 with the source line") {
            val wall = r(x = 522f, y = -400f, w = 1500f, h = 1500f)
            val (res, misses) = ermRun(endLabelRects = { mapOf("e1" to listOf(wall)) })
            misses shouldHaveSize 1
            misses.single().sourceLine shouldBe 3
            res.hintNotHonoured shouldHaveSize 1
        }

        test("obstaclesOf: empty endLabels equal the former rectangle list; end labels add one EDGE_LABEL rect each, owned by the edge") {
            val layout = layoutOf()
            val before = Sysml2NotePlacement.obstaclesOf(layout = layout, edgeLabels = { if (it == "e1") listOf("name") else emptyList() })
            val same =
                Sysml2NotePlacement.obstaclesOf(
                    layout = layout,
                    edgeLabels = { if (it == "e1") listOf("name") else emptyList() },
                    endLabels = emptyMap(),
                )
            same.rects.map { Triple(it.kind, it.core, it.ownerId) } shouldBe before.rects.map { Triple(it.kind, it.core, it.ownerId) }

            val a = r(x = 530f, y = 300f, w = 10f, h = 10f)
            val b = r(x = 560f, y = 300f, w = 10f, h = 10f)
            val with =
                Sysml2NotePlacement.obstaclesOf(
                    layout = layout,
                    edgeLabels = { if (it == "e1") listOf("name") else emptyList() },
                    endLabels = mapOf("e1" to listOf(a, b)),
                )
            with.rects shouldHaveSize before.rects.size + 2
            val added = with.rects.filter { it.core == a || it.core == b }
            added.map { it.kind }.toSet() shouldBe setOf(ObstacleKind.EDGE_LABEL)
            added.map { it.ownerId }.toSet() shouldBe setOf("e1")
            // the estimated centre label stays
            with.rects.count { it.kind == ObstacleKind.EDGE_LABEL && it.ownerId == "e1" } shouldBe 3
        }
    })
