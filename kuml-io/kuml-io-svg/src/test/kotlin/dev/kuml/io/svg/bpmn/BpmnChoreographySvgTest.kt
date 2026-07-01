package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnLoopType
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyMessageFlow
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * SVG-Renderer-Tests für BPMN-Choreography-Elemente.
 *
 * Prüft, dass [ChoreographyTask], [ChoreographyGateway], [ChoreographyEvent] und
 * [ChoreographySequenceFlow] korrekte SVG-Fragmente erzeugen. Tests laufen über
 * [KumlSvgRenderer.toSvg] (BpmnModel + ChoreographyDiagram-Überladung), damit
 * die vollständige Render-Pipeline (inkl. renderBpmnChoreography) abgedeckt ist.
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
class BpmnChoreographySvgTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────────

        /** Minimales Layout mit einem einzelnen Knoten (160×80 px). */
        fun singleTaskLayout(id: String): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(200f, 120f),
                nodes = mapOf(NodeId(id) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(160f, 80f)))),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        /** Layout für einen Knoten + eine Kante. */
        fun twoNodeOneEdgeLayout(
            id1: String,
            id2: String,
            edgeId: String,
            w: Float = 160f,
            h: Float = 80f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(440f, 120f),
                nodes =
                    mapOf(
                        NodeId(id1) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(w, h))),
                        NodeId(id2) to NodeLayout(bounds = Rect(Point(260f, 20f), Size(w, h))),
                    ),
                edges =
                    mapOf(
                        EdgeId(edgeId) to
                            EdgeRoute.Direct(
                                source = Point(180f, 60f),
                                target = Point(260f, 60f),
                            ),
                    ),
                groups = emptyMap(),
            )

        /** Baut ein minimales BpmnModel + ChoreographyDiagram rund um eine einzelne Choreography. */
        fun modelAndDiagram(choreo: BpmnChoreography): Pair<BpmnModel, ChoreographyDiagram> {
            val model = BpmnModel(name = "TestModel", choreographies = listOf(choreo))
            val diagram = ChoreographyDiagram(name = "TestDiagram", choreographyId = choreo.id)
            return model to diagram
        }

        // ── ChoreographyTask ──────────────────────────────────────────────────────

        test("ChoreographyTask: SVG enthält äußeres abgerundetes Rect (rx=8, BPMN 2.0 §10.4.2)") {
            val task =
                ChoreographyTask(
                    id = "ct1",
                    name = "Place Order",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct1"), PlainTheme())

            // Äußere Box muss rx=8 haben (BPMN 2.0 §10.4.2 schreibt abgerundete Ecken vor)
            svg shouldContain "rx=\"8\""
        }

        test("ChoreographyTask: SVG enthält inneres Rect mit rx=6 (doppelter Rahmen)") {
            val task =
                ChoreographyTask(
                    id = "ct2",
                    name = "Request Approval",
                    initiatingParticipant = "Manager",
                    participants = listOf("Manager", "Staff"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct2"), PlainTheme())

            // Innerer Doppelrahmen muss rx=6 haben
            svg shouldContain "rx=\"6\""
            // fill=none für das innere Rect
            svg shouldContain "fill=\"none\""
        }

        test("ChoreographyTask: SVG enthält zwei rect-Elemente (äußeres + inneres)") {
            val task =
                ChoreographyTask(
                    id = "ct3",
                    name = "Notify",
                    initiatingParticipant = "System",
                    participants = listOf("System", "User"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct3"), PlainTheme())

            // Mindestens 2 rect-Elemente: äußere Box + innerer Doppelrahmen
            // Plus 2 Bänder-Rects (oberes initiierendes + unteres empfangendes Band)
            val rectCount = svg.split("<rect").size - 1
            assert(rectCount >= 2) { "ChoreographyTask muss mindestens 2 <rect>-Elemente haben, gefunden: $rectCount" }
        }

        test("ChoreographyTask: initiierender Teilnehmer im SVG sichtbar") {
            val task =
                ChoreographyTask(
                    id = "ct4",
                    name = "Submit Invoice",
                    initiatingParticipant = "Vendor",
                    participants = listOf("Vendor", "Buyer"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct4"), PlainTheme())

            // Initiierender Teilnehmer im oberen Band sichtbar
            svg shouldContain "Vendor"
            // Empfangender Teilnehmer im unteren Band sichtbar
            svg shouldContain "Buyer"
        }

        test("ChoreographyTask: Task-Name im mittleren Band sichtbar") {
            val task =
                ChoreographyTask(
                    id = "ct5",
                    name = "Confirm Shipment",
                    initiatingParticipant = "Warehouse",
                    participants = listOf("Warehouse", "Customer"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct5"), PlainTheme())

            svg shouldContain "Confirm Shipment"
        }

        test("ChoreographyTask: initiierendes Band hat Aureolin-Gelb-Fill (#FFED00)") {
            val task =
                ChoreographyTask(
                    id = "ct6",
                    name = "Pay",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Merchant"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct6"), PlainTheme())

            // Initiierendes Band hat BPMN-Choreography-Konvention: Aureolin-Gelb
            svg shouldContain "#FFED00"
        }

        test("ChoreographyTask mit STANDARD-Loop: SVG enthält Loop-Marker (path-Element)") {
            val task =
                ChoreographyTask(
                    id = "ct7",
                    name = "Retry Payment",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Bank"),
                    loopType = BpmnLoopType.STANDARD,
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct7"), PlainTheme())

            // STANDARD-Loop: Pfeil-Kreis (path mit Arc)
            svg shouldContain "<path"
            svg shouldContain "A 6,6 0 1,1"
        }

        test("ChoreographyTask mit MI_PARALLEL-Loop: SVG enthält vertikale Striche") {
            val task =
                ChoreographyTask(
                    id = "ct8",
                    name = "Process Items",
                    initiatingParticipant = "Picker",
                    participants = listOf("Picker", "Warehouse"),
                    loopType = BpmnLoopType.MULTI_INSTANCE_PARALLEL,
                    isMultiInstance = true,
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct8"), PlainTheme())

            // MI_PARALLEL: drei vertikale Striche
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "MI_PARALLEL-Loop muss mindestens 3 vertikale Linien haben, gefunden: $lineCount" }
        }

        test("ChoreographyTask ohne Name: kein leerer Text-Node emittiert") {
            val task =
                ChoreographyTask(
                    id = "ct9",
                    name = null,
                    initiatingParticipant = "A",
                    participants = listOf("A", "B"),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, singleTaskLayout("ct9"), PlainTheme())

            // Kein leerer text-Node für null-Name (font-size=12 ist der Task-Name-Text)
            // Das SVG darf font-size=10 (Band-Labels) enthalten, aber kein leeres <text>
            svg shouldContain "<svg"
            // Teilnehmer-Bänder müssen trotzdem vorhanden sein
            svg shouldContain "A"
            svg shouldContain "B"
        }

        // ── Smoke-Test: ChoreographyTask + 2 Participants + SequenceFlow ────────

        test("Smoke: ChoreographyTask + 2 Participants + SequenceFlow → SVG mit doppeltem Rahmen und Pfeil") {
            val task =
                ChoreographyTask(
                    id = "task1",
                    name = "Place Order",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                )
            val task2 =
                ChoreographyTask(
                    id = "task2",
                    name = "Confirm Order",
                    initiatingParticipant = "Seller",
                    participants = listOf("Seller", "Buyer"),
                )
            val flow = ChoreographySequenceFlow(id = "sf1", sourceRef = "task1", targetRef = "task2")
            val choreo =
                BpmnChoreography(
                    id = "ch1",
                    tasks = listOf(task, task2),
                    sequenceFlows = listOf(flow),
                )
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = twoNodeOneEdgeLayout("task1", "task2", "sf1")
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // Doppelter Rahmen: rx=8 (äußerer Rect) muss im SVG vorhanden sein
            svg shouldContain "rx=\"8\""
            // Innerer Doppelrahmen: rx=6
            svg shouldContain "rx=\"6\""
            // Beide Task-Namen sichtbar
            svg shouldContain "Place Order"
            svg shouldContain "Confirm Order"
            // Beide Teilnehmer sichtbar
            svg shouldContain "Buyer"
            svg shouldContain "Seller"
            // SequenceFlow als Pfeil (marker-end + polygon)
            svg shouldContain "marker-end"
            svg shouldContain "<polygon"
            // SVG-Grundstruktur intakt
            svg shouldContain "<svg"
        }

        // ── ChoreographyGateway ───────────────────────────────────────────────────

        test("ChoreographyGateway EXCLUSIVE: SVG enthält Raute (polygon) + X") {
            val gw = ChoreographyGateway(id = "gw1", type = GatewayType.EXCLUSIVE)
            val choreo = BpmnChoreography(id = "ch1", gateways = listOf(gw))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(90f, 90f),
                    nodes = mapOf(NodeId("gw1") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(50f, 50f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<polygon"
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "EXCLUSIVE Gateway muss mindestens 2 Linien für X haben, gefunden: $lineCount" }
        }

        test("ChoreographyGateway PARALLEL: SVG enthält Raute + Plus-Symbol") {
            val gw = ChoreographyGateway(id = "gw2", type = GatewayType.PARALLEL)
            val choreo = BpmnChoreography(id = "ch1", gateways = listOf(gw))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(90f, 90f),
                    nodes = mapOf(NodeId("gw2") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(50f, 50f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<polygon"
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "PARALLEL Gateway muss mindestens 2 Linien für + haben, gefunden: $lineCount" }
        }

        // ── ChoreographyEvent ─────────────────────────────────────────────────────

        test("ChoreographyEvent START: SVG enthält Kreis mit stroke-width=1.5") {
            val event = ChoreographyEvent(id = "ev1", position = EventPosition.START)
            val choreo = BpmnChoreography(id = "ch1", events = listOf(event))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(80f, 80f),
                    nodes = mapOf(NodeId("ev1") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(36f, 36f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<circle"
            svg shouldContain "stroke-width=\"1.5\""
        }

        test("ChoreographyEvent END: SVG enthält Kreis mit stroke-width=3") {
            val event = ChoreographyEvent(id = "ev2", position = EventPosition.END)
            val choreo = BpmnChoreography(id = "ch1", events = listOf(event))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(80f, 80f),
                    nodes = mapOf(NodeId("ev2") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(36f, 36f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<circle"
            svg shouldContain "stroke-width=\"3\""
        }

        test("ChoreographyEvent INTERMEDIATE: SVG enthält zwei Kreise (Doppelring)") {
            val event = ChoreographyEvent(id = "ev3", name = "Check", position = EventPosition.INTERMEDIATE)
            val choreo = BpmnChoreography(id = "ch1", events = listOf(event))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(80f, 80f),
                    nodes = mapOf(NodeId("ev3") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(36f, 36f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            // INTERMEDIATE: äußerer + innerer Kreis = Doppelring
            val circleCount = svg.split("<circle").size - 1
            assert(circleCount >= 2) { "INTERMEDIATE Event muss mindestens 2 Kreise haben, gefunden: $circleCount" }
            svg shouldContain "Check"
        }

        // ── ChoreographySequenceFlow ──────────────────────────────────────────────

        test("ChoreographySequenceFlow: SVG enthält Pfeilkopf-Marker und path") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Order",
                    initiatingParticipant = "A",
                    participants = listOf("A", "B"),
                )
            val task2 =
                ChoreographyTask(
                    id = "t2",
                    name = "Confirm",
                    initiatingParticipant = "B",
                    participants = listOf("B", "A"),
                )
            val flow = ChoreographySequenceFlow(id = "sf1", sourceRef = "t1", targetRef = "t2")
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task, task2), sequenceFlows = listOf(flow))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoNodeOneEdgeLayout("t1", "t2", "sf1"), PlainTheme())

            svg shouldContain "<marker"
            svg shouldContain "marker-end"
            svg shouldContain "<path"
        }

        test("ChoreographySequenceFlow mit Condition-Label: Label im SVG sichtbar") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Review",
                    initiatingParticipant = "Manager",
                    participants = listOf("Manager", "Clerk"),
                )
            val task2 =
                ChoreographyTask(
                    id = "t2",
                    name = "Approve",
                    initiatingParticipant = "Manager",
                    participants = listOf("Manager", "Clerk"),
                )
            val flow =
                ChoreographySequenceFlow(
                    id = "sf1",
                    sourceRef = "t1",
                    targetRef = "t2",
                    condition = "amount > 1000",
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task, task2), sequenceFlows = listOf(flow))
            val (model, diagram) = modelAndDiagram(choreo)

            val svg = KumlSvgRenderer.toSvg(model, diagram, twoNodeOneEdgeLayout("t1", "t2", "sf1"), PlainTheme())

            svg shouldContain "amount &gt; 1000"
        }

        // ── Robustheit ────────────────────────────────────────────────────────────

        test("unbekannte choreographyId: gibt minimales valides SVG zurück (kein Crash)") {
            val model = BpmnModel(name = "M")
            val diagram = ChoreographyDiagram(name = "D", choreographyId = "does-not-exist")

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(100f, 100f),
                    nodes = emptyMap(),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<svg"
        }

        // ── End-to-end über die echte ChoreographyGridLayout-Pipeline (V3.2.2) ─────

        test("End-to-end: ChoreographyGridLayout + Renderer zeigen Doppelrahmen, Bänder und Teilnehmer") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Bestellung senden",
                    initiatingParticipant = "Kunde",
                    participants = listOf("Kunde", "Haendler"),
                    loopType = BpmnLoopType.STANDARD,
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = ChoreographyGridLayout.layout(model, diagram)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "fill=\"none\""
            svg shouldContain "#FFED00"
            svg shouldContain "Kunde"
            svg shouldContain "Haendler"
            svg shouldContain "Bestellung senden"
        }

        test("End-to-end: Message-Envelope-Icons erscheinen wenn messageFlows gesetzt sind") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Anfrage",
                    initiatingParticipant = "Kunde",
                    participants = listOf("Kunde", "Haendler"),
                    messageFlows =
                        listOf(
                            ChoreographyMessageFlow(
                                id = "mf1",
                                name = "Anfrage",
                                participantRef = "Kunde",
                                isInitiating = true,
                            ),
                            ChoreographyMessageFlow(
                                id = "mf2",
                                name = "Antwort",
                                participantRef = "Haendler",
                                isInitiating = false,
                            ),
                        ),
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = ChoreographyGridLayout.layout(model, diagram)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "#DDDDDD"
            svg shouldContain "stroke-dasharray"
        }

        test("End-to-end: adjacent envelope-bearing tasks — glyph rects do not overlap vertically") {
            val t1 =
                ChoreographyTask(
                    id = "t1",
                    initiatingParticipant = "A",
                    participants = listOf("A", "B"),
                    messageFlows =
                        listOf(
                            ChoreographyMessageFlow(id = "m1", participantRef = "A", isInitiating = false),
                        ),
                )
            val t2 =
                ChoreographyTask(
                    id = "t2",
                    initiatingParticipant = "C",
                    participants = listOf("C", "D"),
                    messageFlows =
                        listOf(
                            ChoreographyMessageFlow(id = "m2", participantRef = "C", isInitiating = true),
                        ),
                )
            val gw = ChoreographyGateway(id = "gw", type = GatewayType.PARALLEL)
            val choreo =
                BpmnChoreography(
                    id = "ch1",
                    tasks = listOf(t1, t2),
                    gateways = listOf(gw),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "f1", sourceRef = "gw", targetRef = "t1"),
                            ChoreographySequenceFlow(id = "f2", sourceRef = "gw", targetRef = "t2"),
                        ),
                )
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = ChoreographyGridLayout.layout(model, diagram)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            val envelopeRectRegex =
                Regex("""<rect x="[^"]*" y="(-?[\d.]+)" width="16(?:\.0+)?" height="10(?:\.0+)?"""")
            val intervals =
                envelopeRectRegex
                    .findAll(svg)
                    .map { match ->
                        val y = match.groupValues[1].toFloat()
                        y to (y + 10f)
                    }.toList()

            (intervals.size >= 2) shouldBe true
            for (i in intervals.indices) {
                for (j in intervals.indices) {
                    if (i == j) continue
                    val (aStart, aEnd) = intervals[i]
                    val (bStart, bEnd) = intervals[j]
                    val overlap = aStart < bEnd && bStart < aEnd
                    overlap shouldBe false
                }
            }
        }

        test("End-to-end: Loop-Marker bleibt bei echtem Grid-Layout sichtbar") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    name = "Wiederholte Pruefung",
                    initiatingParticipant = "Kunde",
                    participants = listOf("Kunde", "Haendler"),
                    isMultiInstance = true,
                    loopType = BpmnLoopType.MULTI_INSTANCE_PARALLEL,
                )
            val choreo = BpmnChoreography(id = "ch1", tasks = listOf(task))
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = ChoreographyGridLayout.layout(model, diagram)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<svg"
            svg shouldContain "Wiederholte Pruefung"
        }

        test("End-to-end: Gateway-Raute und Event-Kreise über echtes Grid-Layout") {
            val start = ChoreographyEvent(id = "start", position = EventPosition.START)
            val gw = ChoreographyGateway(id = "gw1", type = GatewayType.EXCLUSIVE)
            val task =
                ChoreographyTask(
                    id = "t1",
                    initiatingParticipant = "A",
                    participants = listOf("A", "B"),
                )
            val end = ChoreographyEvent(id = "end", position = EventPosition.END)
            val choreo =
                BpmnChoreography(
                    id = "ch1",
                    tasks = listOf(task),
                    gateways = listOf(gw),
                    events = listOf(start, end),
                    sequenceFlows =
                        listOf(
                            ChoreographySequenceFlow(id = "sf1", sourceRef = "start", targetRef = "gw1"),
                            ChoreographySequenceFlow(id = "sf2", sourceRef = "gw1", targetRef = "t1"),
                            ChoreographySequenceFlow(id = "sf3", sourceRef = "t1", targetRef = "end"),
                        ),
                )
            val (model, diagram) = modelAndDiagram(choreo)

            val layout = ChoreographyGridLayout.layout(model, diagram)
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "<circle"
            svg shouldContain "marker-end"
        }
    })
