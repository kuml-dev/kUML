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

        test("collaboration: BELOW a task of the bottom pool lands outside all pools, 24 px away, reported as left its pool") {
            val (res, misses) = collab(NotePlacement.BELOW, "tBot", bottomTask = taskBottomNearBottom)
            val n = res.layout.noteRect()
            // Nacharbeit 120: the pool is the home of the note; leaving it is reported once
            misses shouldHaveSize 1
            misses.single().outsideHomeLane shouldBe true
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

        // ------------------------------------------------------------ Nacharbeit 120: home lane

        fun laneRect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = rect(x = x, y = y, w = w, h = h)

        fun lanePlace(
            lanes: List<LaneRect>,
            task: Rect,
            placement: NotePlacement?,
            extra: Map<String, Rect> = emptyMap(),
            anchor: String = "t1",
        ): Pair<NotePlacementResult, List<PlacementHintMiss>> {
            val (a, l) = note(placement = placement, target = anchor, line = 3)
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 900f, height = 700f),
                    nodes =
                        (mapOf("t1" to task, "n1" to rect(x = 860f, y = 300f, w = 100f, h = 40f)) + extra)
                            .entries
                            .associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
                    edges = mapOf(EdgeId("l1") to EdgeRoute.Direct(source = Point(x = 860f, y = 320f), target = Point(x = 420f, y = 170f))),
                    groups = emptyMap(),
                )
            return NotePlacementMissCollector.collecting {
                BpmnNotePlacement.place(
                    annotations = listOf(a),
                    associations = listOf(l),
                    layout = layout,
                    containers = lanes,
                    anchorExtras = emptyMap(),
                    bands = emptyMap(),
                    labelled = emptyMap(),
                    config = NotePlacementConfig(),
                )
            }
        }

        fun inside(
            n: Rect,
            lane: Rect,
        ) = n.left >= lane.left && n.right <= lane.right && n.top >= lane.top && n.bottom <= lane.bottom

        val laneTall = laneRect(x = 40f, y = 40f, w = 800f, h = 260f)
        val laneBelow = laneRect(x = 40f, y = 300f, w = 800f, h = 260f)

        test("lanes: a hinted note stays in the lane of its anchor") {
            val lanes = listOf(LaneRect("l1", laneTall), LaneRect("l2", laneBelow))
            val (res, misses) = lanePlace(lanes = lanes, task = rect(x = 300f, y = 70f, w = 120f, h = 60f), placement = NotePlacement.BELOW)
            misses.shouldBeEmpty()
            inside(res.layout.noteRect(), laneTall) shouldBe true
            (res.layout.noteRect().top >= 130f - 0.5f) shouldBe true
        }

        test("lanes: no free position in the lane gives a placement in another lane and exactly one W004") {
            val lanes = listOf(LaneRect("l1", laneTall), LaneRect("l2", laneBelow))
            // the task sits at the bottom of its lane: BELOW has no room in the lane, but is free in the lane below
            val (res, misses) =
                lanePlace(
                    lanes = lanes,
                    task = rect(x = 300f, y = 230f, w = 120f, h = 50f),
                    placement = NotePlacement.BELOW,
                )
            misses shouldHaveSize 1
            misses.single().outsideHomeLane shouldBe true
            misses.single().noteId shouldBe "n1"
            misses.single().sourceLine shouldBe 3
            misses.single().message().endsWith("outside the lane or pool of its anchor") shouldBe true
            (NodeId("n1") in res.layout.nodes) shouldBe true
            inside(res.layout.noteRect(), laneBelow) shouldBe true
            res.hintNotHonoured shouldHaveSize 1
        }

        test("lanes: a total miss is reported once, not twice") {
            val lanes = listOf(LaneRect("l1", laneTall), LaneRect("l2", laneBelow))
            val (res, misses) =
                lanePlace(
                    lanes = lanes,
                    task = rect(x = 300f, y = 70f, w = 120f, h = 60f),
                    placement = NotePlacement.ABOVE,
                    extra = mapOf("roof" to rect(x = -400f, y = -400f, w = 1800f, h = 440f)),
                )
            misses shouldHaveSize 1
            res.hintNotHonoured shouldHaveSize 1
            (NodeId("n1") in res.layout.nodes) shouldBe true
        }

        test("lanes: without containers or without a hint nothing changes and nothing is reported") {
            val (res, misses) =
                lanePlace(
                    lanes = emptyList(),
                    task = rect(x = 300f, y = 70f, w = 120f, h = 60f),
                    placement = NotePlacement.RIGHT,
                )
            misses.shouldBeEmpty()
            (res.layout.noteRect().left >= 420f - 0.5f) shouldBe true
        }

        fun home(
            lanes: List<LaneRect>,
            at: Rect,
            extras: Map<String, AnchorGeometry> = emptyMap(),
            bands: Map<String, TitleBand> = emptyMap(),
            target: String = "t1",
        ): Map<String, Rect> {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 900f, height = 700f),
                    nodes = mapOf(NodeId("t1") to NodeLayout(bounds = at)),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val input = NotePlacementInput(noteId = "n1", body = "x", anchors = listOf(NoteAnchorRef(linkId = "l1", targetId = target)))
            return BpmnNotePlacement.homeLanesOf(
                inputs = listOf(input),
                containers = lanes,
                layout = layout,
                anchorExtras = extras,
                bands = bands,
            )
        }

        test("home lane: a pool without lanes is its own home") {
            val pool = laneRect(x = 0f, y = 0f, w = 800f, h = 300f)
            home(lanes = listOf(LaneRect("pool", pool)), at = rect(x = 100f, y = 100f, w = 100f, h = 50f)) shouldBe mapOf("n1" to pool)
        }

        test("home lane: an anchor in no container is unrestricted") {
            val pool = laneRect(x = 0f, y = 0f, w = 200f, h = 200f)
            home(lanes = listOf(LaneRect("pool", pool)), at = rect(x = 500f, y = 500f, w = 100f, h = 50f)) shouldBe emptyMap()
        }

        test("home lane: the innermost container wins, the first one on an equal area (boundary event on a lane edge)") {
            val outer = laneRect(x = 0f, y = 0f, w = 800f, h = 400f)
            val nested = laneRect(x = 0f, y = 0f, w = 400f, h = 200f)
            val task = rect(x = 100f, y = 50f, w = 100f, h = 50f)
            home(lanes = listOf(LaneRect("outer", outer), LaneRect("nested", nested)), at = task) shouldBe mapOf("n1" to nested)
            home(lanes = listOf(LaneRect("nested", nested), LaneRect("outer", outer)), at = task) shouldBe mapOf("n1" to nested)
            // centre exactly on the shared edge x = 100: both lanes contain it, equal area, the first wins
            val left = laneRect(x = 0f, y = 0f, w = 100f, h = 100f)
            val right = laneRect(x = 100f, y = 0f, w = 100f, h = 100f)
            val onEdge = rect(x = 90f, y = 40f, w = 20f, h = 20f)
            home(lanes = listOf(LaneRect("left", left), LaneRect("right", right)), at = onEdge) shouldBe mapOf("n1" to left)
            home(lanes = listOf(LaneRect("right", right), LaneRect("left", left)), at = onEdge) shouldBe mapOf("n1" to right)
        }

        test("home lane: a note anchored on a pool itself has no home") {
            val pool = laneRect(x = 0f, y = 0f, w = 800f, h = 300f)
            home(
                lanes = listOf(LaneRect("pool", pool)),
                at = rect(x = 0f, y = 0f, w = 0f, h = 0f),
                extras = mapOf("pool" to AnchorGeometry.Around(pool)),
                target = "pool",
            ) shouldBe emptyMap()
        }
    })
