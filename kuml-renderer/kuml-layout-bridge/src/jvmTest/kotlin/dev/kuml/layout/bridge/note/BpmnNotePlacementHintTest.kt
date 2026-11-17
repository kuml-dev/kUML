@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
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

private val processTask = rect(x = 400f, y = 300f, w = 120f, h = 60f)
private val elkNote = rect(x = 600f, y = 300f, w = 100f, h = 40f)

private fun note(
    placement: NotePlacement?,
    target: String,
    line: Int? = null,
) = BpmnTextAnnotation(note = KumlNote(id = "n1", body = "Note text", placement = placement, sourceLine = line)) to
    BpmnAssociation(link = KumlNoteLink(id = "l1", noteId = "n1", targetId = target))

private fun processLayout() =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(width = 900f, height = 700f),
        nodes = mapOf(NodeId("t1") to NodeLayout(bounds = processTask), NodeId("n1") to NodeLayout(bounds = elkNote)),
        edges = mapOf(EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 600f, y = 320f), target = Point(x = 520f, y = 330f))),
        groups = emptyMap(),
    )

private fun processDiagram(placement: NotePlacement?): KumlDiagram {
    val (a, l) = note(placement = placement, target = "t1", line = 7)
    return KumlDiagram(id = "d", name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(BpmnTask(id = "t1", name = "T"), a, l))
}

private val poolTop = rect(x = 40f, y = 40f, w = 800f, h = 300f)
private val poolBottom = rect(x = 40f, y = 360f, w = 800f, h = 300f)
private val taskTop = rect(x = 300f, y = 140f, w = 120f, h = 60f)
private val taskBottomMid = rect(x = 300f, y = 460f, w = 120f, h = 60f)
private val taskBottomNearBottom = rect(x = 300f, y = 590f, w = 120f, h = 50f)
private val taskBottomUnderRoof = rect(x = 300f, y = 400f, w = 120f, h = 60f)
private val roof = rect(x = -400f, y = -400f, w = 1800f, h = 780f)

private fun collabLayout(
    bottomTask: Rect,
    extra: Map<String, Rect> = emptyMap(),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = Size(width = 900f, height = 760f),
    nodes =
        mapOf(
            NodeId("poolTop") to NodeLayout(bounds = rect(x = 40f, y = 40f, w = 0f, h = 0f)),
            NodeId("poolBottom") to NodeLayout(bounds = rect(x = 40f, y = 360f, w = 0f, h = 0f)),
            NodeId("tTop") to NodeLayout(bounds = taskTop),
            NodeId("tBot") to NodeLayout(bounds = bottomTask),
            NodeId("n1") to NodeLayout(bounds = rect(x = 860f, y = 300f, w = 100f, h = 40f)),
        ) + extra.entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
    edges = mapOf(EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 860f, y = 320f), target = Point(x = 420f, y = 170f))),
    groups = mapOf(GroupId("poolTop") to GroupLayout(bounds = poolTop), GroupId("poolBottom") to GroupLayout(bounds = poolBottom)),
)

private fun collabModel(
    placement: NotePlacement?,
    target: String,
    line: Int? = null,
): Pair<BpmnModel, CollaborationDiagram> {
    val (a, l) = note(placement = placement, target = target, line = line)
    val procTop = BpmnProcess(id = "pTop", flowNodes = listOf(BpmnTask(id = "tTop", name = "Top")))
    val procBot = BpmnProcess(id = "pBot", flowNodes = listOf(BpmnTask(id = "tBot", name = "Bottom")))
    val collab =
        BpmnCollaboration(
            id = "c",
            participants =
                listOf(
                    BpmnParticipant(id = "poolTop", name = "Top", processRef = "pTop"),
                    BpmnParticipant(id = "poolBottom", name = "Bottom", processRef = "pBot"),
                ),
            textAnnotations = listOf(a),
            associations = listOf(l),
        )
    return BpmnModel(name = "M", processes = listOf(procTop, procBot), collaborations = listOf(collab)) to
        CollaborationDiagram(name = "C", collaborationId = "c")
}

private fun LayoutResult.noteRect(): Rect = nodes.getValue(NodeId("n1")).bounds

class BpmnNotePlacementHintTest :
    FunSpec({
        fun process(placement: NotePlacement?) =
            NotePlacementMissCollector.collecting {
                BpmnNotePlacement.placeProcess(diagram = processDiagram(placement), layout = processLayout())
            }

        fun collab(
            placement: NotePlacement?,
            target: String,
            bottomTask: Rect = taskBottomMid,
            extra: Map<String, Rect> = emptyMap(),
        ) = NotePlacementMissCollector.collecting {
            val (model, diagram) = collabModel(placement = placement, target = target, line = 7)
            BpmnNotePlacement.placeCollaboration(
                model = model,
                diagram = diagram,
                layout = collabLayout(bottomTask = bottomTask, extra = extra),
            )
        }

        test("no hint and NEAR leave the layout untouched") {
            val layout = processLayout()
            for (p in listOf(null, NotePlacement.NEAR)) {
                val (res, misses) = process(p)
                (res.layout === layout || res.layout == layout) shouldBe true
                misses.shouldBeEmpty()
                val (cres, cmisses) = collab(p, "tTop")
                cres.layout shouldBe collabLayout(taskBottomMid)
                cmisses.shouldBeEmpty()
            }
        }

        test("process: each side hint puts the note on that side of its anchor") {
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (res, misses) = process(side)
                val n = res.layout.noteRect()
                val t =
                    res.layout.nodes
                        .getValue(NodeId("t1"))
                        .bounds
                misses.shouldBeEmpty()
                val ok =
                    when (side) {
                        NotePlacement.ABOVE -> n.origin.y + n.size.height <= t.origin.y + 0.5f
                        NotePlacement.BELOW -> n.origin.y >= t.origin.y + t.size.height - 0.5f
                        NotePlacement.LEFT -> n.origin.x + n.size.width <= t.origin.x + 0.5f
                        else -> n.origin.x >= t.origin.x + t.size.width - 0.5f
                    }
                ok shouldBe true
            }
        }

        test("collaboration: BELOW a task of the bottom pool lands outside all pools, 24 px away") {
            val (res, misses) = collab(NotePlacement.BELOW, "tBot", bottomTask = taskBottomNearBottom)
            val n = res.layout.noteRect()
            misses.shouldBeEmpty()
            val bottomOfPool =
                res.layout.groups
                    .getValue(GroupId("poolBottom"))
                    .bounds
                    .let { it.origin.y + it.size.height }
            (n.origin.y >= bottomOfPool + 24f - 0.5f) shouldBe true
        }

        test("collaboration: RIGHT in a pool with room stays in the pool, 16 px from its border, clear of the title band") {
            val (res, misses) = collab(NotePlacement.RIGHT, "tTop")
            val n = res.layout.noteRect()
            val pool =
                res.layout.groups
                    .getValue(GroupId("poolTop"))
                    .bounds
            misses.shouldBeEmpty()
            (n.origin.x >= taskTop.origin.x + taskTop.size.width - 0.5f) shouldBe true
            (n.origin.x + n.size.width <= pool.origin.x + pool.size.width - 16f + 0.5f) shouldBe true
            (n.origin.x >= pool.origin.x + BpmnNotePlacement.POOL_BAND_PX) shouldBe true
        }

        test("collaboration: ABOVE with no free space above is reported once as a miss with the source line") {
            val (res, misses) = collab(NotePlacement.ABOVE, "tBot", bottomTask = taskBottomUnderRoof, extra = mapOf("roof" to roof))
            misses shouldHaveSize 1
            misses.single().noteId shouldBe "n1"
            misses.single().wanted shouldBe NotePlacement.ABOVE
            misses.single().sourceLine shouldBe 7
            res.hintNotHonoured shouldHaveSize 1
        }

        test("collaboration: an annotation on a pool with ABOVE lies above the pool") {
            val (res, misses) = collab(NotePlacement.ABOVE, "poolTop")
            val n = res.layout.noteRect()
            val pool =
                res.layout.groups
                    .getValue(GroupId("poolTop"))
                    .bounds
            misses.shouldBeEmpty()
            (n.origin.y + n.size.height <= pool.origin.y + 0.5f) shouldBe true
        }

        test("placement is deterministic") {
            val a = collab(NotePlacement.RIGHT, "tTop").first.layout
            val b = collab(NotePlacement.RIGHT, "tTop").first.layout
            a shouldBe b
            process(NotePlacement.LEFT).first.layout shouldBe process(NotePlacement.LEFT).first.layout
        }
    })
