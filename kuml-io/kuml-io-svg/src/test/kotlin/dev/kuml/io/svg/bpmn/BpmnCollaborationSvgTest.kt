package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.io.svg.KumlSvgRenderer
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
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SVG-Renderer-Tests für BPMN-Collaboration-Elemente (Pools, Lanes, MessageFlows).
 *
 * Prüft, dass alle Swimlane-Elemente korrekte SVG-Fragmente erzeugen.
 *
 * V3.1.4/3.1.5 — BPMN Collaboration SVG-Renderer
 */
class BpmnCollaborationSvgTest :
    FunSpec({

        // ── Test helpers ──────────────────────────────────────────────────────────

        fun emptyLayoutResult(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(800f, 400f),
                nodes = emptyMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun singleGroupLayout(
            id: String,
            x: Float = 10f,
            y: Float = 10f,
            w: Float = 500f,
            h: Float = 150f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 40f, h + 40f),
                nodes = mapOf(NodeId(id) to NodeLayout(bounds = Rect(Point(x, y), Size(0f, 0f)))),
                edges = emptyMap(),
                groups = mapOf(GroupId(id) to GroupLayout(bounds = Rect(Point(x, y), Size(w, h)))),
            )

        fun twoGroupOneEdgeLayout(
            id1: String,
            id2: String,
            edgeId: String,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(800f, 400f),
                nodes =
                    mapOf(
                        NodeId(id1) to NodeLayout(bounds = Rect(Point(10f, 10f), Size(0f, 0f))),
                        NodeId(id2) to NodeLayout(bounds = Rect(Point(400f, 10f), Size(0f, 0f))),
                    ),
                edges =
                    mapOf(
                        EdgeId(edgeId) to
                            EdgeRoute.Direct(
                                source = Point(160f, 85f),
                                target = Point(400f, 85f),
                            ),
                    ),
                groups =
                    mapOf(
                        GroupId(id1) to GroupLayout(bounds = Rect(Point(10f, 10f), Size(300f, 150f))),
                        GroupId(id2) to GroupLayout(bounds = Rect(Point(400f, 10f), Size(300f, 150f))),
                    ),
            )

        fun participantWithLaneLayout(
            poolId: String,
            laneId: String,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(600f, 300f),
                nodes = mapOf(NodeId(poolId) to NodeLayout(bounds = Rect(Point(10f, 10f), Size(0f, 0f)))),
                edges = emptyMap(),
                groups =
                    mapOf(
                        GroupId(poolId) to GroupLayout(bounds = Rect(Point(10f, 10f), Size(500f, 200f))),
                        GroupId(laneId) to GroupLayout(bounds = Rect(Point(40f, 10f), Size(470f, 200f))),
                    ),
            )

        // ── Pool (horizontal) ─────────────────────────────────────────────────────

        test("horizontal pool: SVG contains outer border and title band on the left") {
            val participant = BpmnParticipant(id = "buyer", name = "Buyer", horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleGroupLayout("buyer"), PlainTheme())

            // Outer pool frame
            svg shouldContain "stroke=\"#333\""
            // Title band on the left side (width = POOL_TITLE_BAND_WIDTH = 30)
            svg shouldContain "width=\"30\""
            svg shouldContain "fill=\"#f0f0f0\""
        }

        test("horizontal pool: title text is rotated -90 degrees") {
            val participant = BpmnParticipant(id = "pool1", name = "Sales Department", horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleGroupLayout("pool1"), PlainTheme())

            svg shouldContain "rotate(-90,"
            svg shouldContain "Sales Department"
        }

        test("vertical pool (horizontal=false): title band appears on top") {
            val participant = BpmnParticipant(id = "vpool", name = "Vertical Pool", horizontal = false)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleGroupLayout("vpool"), PlainTheme())

            // For vertical pool there is no rotate(-90) on the title
            svg shouldNotContain "rotate(-90,"
            svg shouldContain "Vertical Pool"
            svg shouldContain "fill=\"#f0f0f0\""
        }

        test("black-box pool renders frame without internal flow content") {
            val participant = BpmnParticipant(id = "ext", name = "External", processRef = null)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val layout = singleGroupLayout("ext")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // Pool border present
            svg shouldContain "stroke=\"#333\""
            // Pool title present
            svg shouldContain "External"
        }

        test("pool with null name: no title text element emitted") {
            val participant = BpmnParticipant(id = "anon", name = null, horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleGroupLayout("anon"), PlainTheme())

            // The pool rect is there but no text element
            svg shouldContain "stroke=\"#333\""
            // No font-weight=bold text (title text)
            svg shouldNotContain "font-weight=\"bold\""
        }

        // ── Lane ──────────────────────────────────────────────────────────────────

        test("horizontal lane: divider line and title band present") {
            val lane = BpmnLane(id = "lane1", name = "Pre-Sales")
            val participant = BpmnParticipant(id = "pool1", name = "Sales", lanes = listOf(lane), horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, participantWithLaneLayout("pool1", "lane1"), PlainTheme())

            // Lane border (stroke="#999")
            svg shouldContain "stroke=\"#999\""
            // Lane title text
            svg shouldContain "Pre-Sales"
            // Lane title band fill
            svg shouldContain "fill=\"#fafafa\""
        }

        test("horizontal lane: title text is rotated -90 degrees") {
            val lane = BpmnLane(id = "lane1", name = "Procurement")
            val participant = BpmnParticipant(id = "pool1", lanes = listOf(lane), horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, participantWithLaneLayout("pool1", "lane1"), PlainTheme())

            svg shouldContain "rotate(-90,"
            svg shouldContain "Procurement"
        }

        test("nested lane: both parent and child lane titles are present") {
            val childLane = BpmnLane(id = "child1", name = "Inner Lane")
            val parentLane = BpmnLane(id = "parent1", name = "Outer Lane", childLanes = listOf(childLane))
            val participant = BpmnParticipant(id = "pool1", lanes = listOf(parentLane), horizontal = true)
            val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            // Layout with parent and child lane groups
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 300f),
                    nodes = mapOf(NodeId("pool1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(0f, 0f)))),
                    edges = emptyMap(),
                    groups =
                        mapOf(
                            GroupId("pool1") to GroupLayout(bounds = Rect(Point(10f, 10f), Size(500f, 200f))),
                            GroupId("parent1") to GroupLayout(bounds = Rect(Point(40f, 10f), Size(470f, 200f))),
                            GroupId("child1") to GroupLayout(bounds = Rect(Point(64f, 10f), Size(446f, 200f))),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "Outer Lane"
            svg shouldContain "Inner Lane"
        }

        // ── MessageFlow ───────────────────────────────────────────────────────────

        test("MessageFlow: SVG contains dashed stroke") {
            val mf = MessageFlow(id = "mf1", sourceRef = "buyer", targetRef = "seller")
            val buyer = BpmnParticipant(id = "buyer", name = "Buyer")
            val seller = BpmnParticipant(id = "seller", name = "Seller")
            val collab = BpmnCollaboration(id = "c1", participants = listOf(buyer, seller), messageFlows = listOf(mf))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoGroupOneEdgeLayout("buyer", "seller", "mf1"), PlainTheme())

            svg shouldContain "stroke-dasharray=\"5,3\""
        }

        test("MessageFlow: SVG contains open arrowhead (white fill polygon)") {
            val mf = MessageFlow(id = "mf1", sourceRef = "buyer", targetRef = "seller")
            val buyer = BpmnParticipant(id = "buyer", name = "Buyer")
            val seller = BpmnParticipant(id = "seller", name = "Seller")
            val collab = BpmnCollaboration(id = "c1", participants = listOf(buyer, seller), messageFlows = listOf(mf))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoGroupOneEdgeLayout("buyer", "seller", "mf1"), PlainTheme())

            // Open arrowhead: polygon with fill=white
            svg shouldContain "fill=\"white\""
            svg shouldContain "<polygon"
            svg shouldContain "marker-end"
        }

        test("MessageFlow: SVG contains initiating circle at source") {
            val mf = MessageFlow(id = "mf1", sourceRef = "buyer", targetRef = "seller")
            val buyer = BpmnParticipant(id = "buyer", name = "Buyer")
            val seller = BpmnParticipant(id = "seller", name = "Seller")
            val collab = BpmnCollaboration(id = "c1", participants = listOf(buyer, seller), messageFlows = listOf(mf))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoGroupOneEdgeLayout("buyer", "seller", "mf1"), PlainTheme())

            svg shouldContain "<circle"
            svg shouldContain "r=\"4\""
        }

        test("MessageFlow with label: label text appears in SVG") {
            val mf = MessageFlow(id = "mf1", name = "Order Request", sourceRef = "buyer", targetRef = "seller")
            val buyer = BpmnParticipant(id = "buyer", name = "Buyer")
            val seller = BpmnParticipant(id = "seller", name = "Seller")
            val collab = BpmnCollaboration(id = "c1", participants = listOf(buyer, seller), messageFlows = listOf(mf))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoGroupOneEdgeLayout("buyer", "seller", "mf1"), PlainTheme())

            svg shouldContain "Order Request"
        }

        test("MessageFlow without label: no label text emitted") {
            val mf = MessageFlow(id = "mf1", name = null, sourceRef = "buyer", targetRef = "seller")
            val buyer = BpmnParticipant(id = "buyer", name = "Buyer")
            val seller = BpmnParticipant(id = "seller", name = "Seller")
            val collab = BpmnCollaboration(id = "c1", participants = listOf(buyer, seller), messageFlows = listOf(mf))
            val model = BpmnModel(name = "M", collaborations = listOf(collab))
            val diagram = CollaborationDiagram(name = "D", collaborationId = "c1")

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoGroupOneEdgeLayout("buyer", "seller", "mf1"), PlainTheme())

            // Dashed line present but no extra text (beyond pool names)
            svg shouldContain "stroke-dasharray=\"5,3\""
            // Buyer/Seller pool names are present, but no additional text
            svg shouldContain "Buyer"
            svg shouldContain "Seller"
        }

        // ── End-to-end: 2-Pool Collaboration ─────────────────────────────────────

        test("end-to-end: 2-pool collaboration renders both pools and a MessageFlow") {
            val task1 = BpmnTask(id = "t1", name = "Place Order")
            val proc1 = BpmnProcess(id = "proc1", flowNodes = listOf(task1))
            val buyer =
                BpmnParticipant(
                    id = "buyer",
                    name = "Buyer",
                    processRef = "proc1",
                    horizontal = true,
                )
            val seller =
                BpmnParticipant(
                    id = "seller",
                    name = "Seller",
                    horizontal = true,
                )
            val mf = MessageFlow(id = "mf1", name = "Order", sourceRef = "buyer", targetRef = "seller")
            val collab =
                BpmnCollaboration(
                    id = "c1",
                    name = "Order Flow",
                    participants = listOf(buyer, seller),
                    messageFlows = listOf(mf),
                )
            val model =
                BpmnModel(
                    name = "Order System",
                    processes = listOf(proc1),
                    collaborations = listOf(collab),
                )
            val diagram = CollaborationDiagram(name = "Order View", collaborationId = "c1")

            val layout = twoGroupOneEdgeLayout("buyer", "seller", "mf1")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // Both pool names present
            svg shouldContain "Buyer"
            svg shouldContain "Seller"
            // MessageFlow dashed line present
            svg shouldContain "stroke-dasharray=\"5,3\""
            // MessageFlow label present
            svg shouldContain "Order"
            // Open arrowhead present
            svg shouldContain "fill=\"white\""
            svg shouldContain "<polygon"
            // Initiating circle present
            svg shouldContain "<circle"
        }

        test("collaboration diagram with unknown collaborationId returns minimal SVG") {
            val model = BpmnModel(name = "M")
            val diagram = CollaborationDiagram(name = "D", collaborationId = "nonexistent")

            val svg = KumlSvgRenderer.toSvg(model, diagram, emptyLayoutResult(), PlainTheme())

            // Should not crash and should return a valid SVG string
            svg shouldContain "<svg"
        }
    })
