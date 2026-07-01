package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnDataObject
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MultiInstanceLoop
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.StandardLoop
import dev.kuml.bpmn.model.TaskType
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
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
 * SVG-Renderer-Tests für BPMN-Elemente.
 *
 * Prüft, dass alle BPMN-Elementtypen korrekte SVG-Fragmente erzeugen:
 * Events, Gateways, Tasks, SubProcesses, CallActivities, DataObjects
 * und SequenceFlows.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
class BpmnSvgRendererTest :
    FunSpec({

        // ── Test-Helpers ──────────────────────────────────────────────────────────

        fun singleNodeLayout(
            id: String,
            w: Float = 120f,
            h: Float = 60f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 20f, h + 20f),
                nodes = mapOf(NodeId(id) to NodeLayout(bounds = Rect(Point(10f, 10f), Size(w, h)))),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun eventLayout(id: String): LayoutResult = singleNodeLayout(id, w = 36f, h = 36f)

        fun gatewayLayout(id: String): LayoutResult = singleNodeLayout(id, w = 50f, h = 50f)

        fun twoNodeLayout(
            id1: String,
            id2: String,
            edgeId: String,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(300f, 100f),
                nodes =
                    mapOf(
                        NodeId(id1) to NodeLayout(bounds = Rect(Point(10f, 30f), Size(36f, 36f))),
                        NodeId(id2) to NodeLayout(bounds = Rect(Point(200f, 30f), Size(36f, 36f))),
                    ),
                edges =
                    mapOf(
                        EdgeId(edgeId) to
                            EdgeRoute.Direct(
                                source = Point(46f, 48f),
                                target = Point(200f, 48f),
                            ),
                    ),
                groups = emptyMap(),
            )

        // ── Event-Tests ───────────────────────────────────────────────────────────

        test("START-Event NONE: SVG enthält dünnen Ring, kein Symbol") {
            val event =
                BpmnEvent(
                    id = "e1",
                    name = "Start",
                    position = EventPosition.START,
                    definition = EventDefinition.NONE,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e1"), PlainTheme())

            svg shouldContain "<circle"
            svg shouldContain "stroke-width=\"1.5\""
            // Kein Symbol-Overlay für NONE
            svg shouldNotContain "<g transform=\"translate"
        }

        test("START-Event MESSAGE: SVG enthält Ring + Umschlag-Symbol") {
            val event =
                BpmnEvent(
                    id = "e2",
                    position = EventPosition.START,
                    definition = EventDefinition.MESSAGE,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e2"), PlainTheme())

            svg shouldContain "<circle"
            // MESSAGE-Symbol enthält rect (Umschlag-Rahmen)
            svg shouldContain "<rect"
            // Polyline für das V des Umschlags
            svg shouldContain "<polyline"
        }

        test("INTERMEDIATE-Event CATCHING: SVG enthält doppelten Ring") {
            val event =
                BpmnEvent(
                    id = "e3",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.NONE,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e3"), PlainTheme())

            // Muss zwei circle-Elemente enthalten (äußerer + innerer Ring)
            val circleCount = svg.split("<circle").size - 1
            assert(circleCount >= 2) { "INTERMEDIATE event must have at least 2 circles, found $circleCount" }
        }

        test("INTERMEDIATE-Event THROWING MESSAGE: SVG enthält gefüllten Umschlag") {
            val event =
                BpmnEvent(
                    id = "e4",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.MESSAGE,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e4"), PlainTheme())

            // Throwing MESSAGE: gefülltes Rect
            svg shouldContain "fill=\"currentColor\""
        }

        test("END-Event NONE: SVG enthält dicken Ring (stroke-width=3)") {
            val event =
                BpmnEvent(
                    id = "e5",
                    position = EventPosition.END,
                    definition = EventDefinition.NONE,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e5"), PlainTheme())

            svg shouldContain "stroke-width=\"3\""
        }

        test("END-Event TERMINATE: SVG enthält dicken Ring + gefüllten Kreis") {
            val event =
                BpmnEvent(
                    id = "e6",
                    position = EventPosition.END,
                    definition = EventDefinition.TERMINATE,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e6"), PlainTheme())

            svg shouldContain "stroke-width=\"3\""
            // TERMINATE-Symbol ist ein gefüllter Kreis
            svg shouldContain "fill=\"currentColor\""
        }

        test("Boundary-Event non-interrupting: SVG enthält gestrichelten Ring") {
            val event =
                BpmnEvent(
                    id = "e7",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.MESSAGE,
                    behaviour = EventBehaviour.CATCHING,
                    interrupting = false,
                    attachedToRef = "task1",
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("e7"), PlainTheme())

            svg shouldContain "stroke-dasharray"
        }

        test("TIMER-Event: SVG enthält Uhr-Zeiger als line-Elemente") {
            val event =
                BpmnEvent(
                    id = "et1",
                    position = EventPosition.START,
                    definition = EventDefinition.TIMER,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("et1"), PlainTheme())

            svg shouldContain "<circle"
            // TIMER symbol: clock face circle + two clock-hand lines
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "TIMER event must have at least 2 lines for clock hands, found $lineCount" }
        }

        test("ERROR-Event: SVG enthält Blitz-Polyline") {
            val event =
                BpmnEvent(
                    id = "ee1",
                    position = EventPosition.END,
                    definition = EventDefinition.ERROR,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("ee1"), PlainTheme())

            // ERROR symbol: filled lightning bolt polyline
            svg shouldContain "<polyline"
            svg shouldContain "fill=\"currentColor\""
        }

        test("ESCALATION-Event: SVG enthält gefüllten Aufwärtspfeil (polygon)") {
            val event =
                BpmnEvent(
                    id = "ee2",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.ESCALATION,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("ee2"), PlainTheme())

            // ESCALATION: filled upward-arrow polygon
            svg shouldContain "<polygon"
            svg shouldContain "fill=\"currentColor\""
        }

        test("SIGNAL catching vs SIGNAL throwing: SVG unterscheidet Füllstil") {
            val catchingEvent =
                BpmnEvent(
                    id = "es_c",
                    position = EventPosition.START,
                    definition = EventDefinition.SIGNAL,
                    behaviour = EventBehaviour.CATCHING,
                )
            val throwingEvent =
                BpmnEvent(
                    id = "es_t",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.SIGNAL,
                    behaviour = EventBehaviour.THROWING,
                )
            val catchingDiagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(catchingEvent))
            val throwingDiagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(throwingEvent))

            val catchingSvg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(catchingDiagram, eventLayout("es_c"), PlainTheme())
            val throwingSvg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(throwingDiagram, eventLayout("es_t"), PlainTheme())

            // Catching SIGNAL: outlined triangle (fill="none")
            catchingSvg shouldContain "<polygon"
            catchingSvg shouldContain "fill=\"none\""
            // Throwing SIGNAL: filled triangle
            throwingSvg shouldContain "<polygon"
            throwingSvg shouldContain "fill=\"currentColor\""
        }

        test("COMPENSATION-Event: SVG enthält Doppel-Pfeil (zwei Polygone)") {
            val event =
                BpmnEvent(
                    id = "ec1",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.COMPENSATION,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("ec1"), PlainTheme())

            // COMPENSATION: two filled left-pointing triangles
            val polygonCount = svg.split("<polygon").size - 1
            assert(polygonCount >= 2) { "COMPENSATION event must have at least 2 polygons for double arrow, found $polygonCount" }
        }

        test("CONDITIONAL-Event: SVG enthält Dokument-Rect mit Zeilen") {
            val event =
                BpmnEvent(
                    id = "ecd1",
                    position = EventPosition.START,
                    definition = EventDefinition.CONDITIONAL,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("ecd1"), PlainTheme())

            // CONDITIONAL: document rect + three horizontal lines
            svg shouldContain "<rect"
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "CONDITIONAL event must have at least 3 lines for document lines, found $lineCount" }
        }

        test("LINK-Event: SVG enthält Pfeil-Polygon (filled)") {
            val event =
                BpmnEvent(
                    id = "el1",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.LINK,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("el1"), PlainTheme())

            // LINK: right-arrow polygon with fill
            svg shouldContain "<polygon"
            svg shouldContain "fill=\"currentColor\""
        }

        test("CANCEL-Event: SVG enthält zwei gekreuzte Linien (X)") {
            val event =
                BpmnEvent(
                    id = "ecn1",
                    position = EventPosition.END,
                    definition = EventDefinition.CANCEL,
                    behaviour = EventBehaviour.THROWING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("ecn1"), PlainTheme())

            // CANCEL: two crossing lines forming an X
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "CANCEL event must have at least 2 lines for X shape, found $lineCount" }
        }

        test("MULTIPLE-Event: SVG enthält Pentagon-Polygon") {
            val event =
                BpmnEvent(
                    id = "em1",
                    position = EventPosition.START,
                    definition = EventDefinition.MULTIPLE,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("em1"), PlainTheme())

            // MULTIPLE: pentagon polygon with 5 points
            svg shouldContain "<polygon"
            // 12,4 20,10 17,20 7,20 4,10 — the MULTIPLE pentagon points
            svg shouldContain "12,4"
        }

        test("PARALLEL_MULTIPLE-Event: SVG enthält Kreuz aus zwei Linien") {
            val event =
                BpmnEvent(
                    id = "epm1",
                    position = EventPosition.START,
                    definition = EventDefinition.PARALLEL_MULTIPLE,
                    behaviour = EventBehaviour.CATCHING,
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(event))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, eventLayout("epm1"), PlainTheme())

            // PARALLEL_MULTIPLE: two lines forming a + cross
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "PARALLEL_MULTIPLE event must have at least 2 lines for + cross, found $lineCount" }
        }

        test("Expanded BpmnSubProcess: Kinder-Knoten im SVG vorhanden und Rahmen-Rect präsent") {
            // Expanded SubProcess frame goes as a group; child task is a separate node.
            // With the dedicated BPMN_PROCESS render path the frame must appear BEFORE
            // the child node in document order (groups-first z-order fix).
            val childTask = BpmnTask(id = "inner-task", name = "Inner Task")
            val subProcess =
                BpmnSubProcess(
                    id = "sp-expanded",
                    name = "Expanded SP",
                    expanded = true,
                    flowElementNodes = listOf(childTask),
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.BPMN_PROCESS,
                    elements = listOf(subProcess, childTask),
                )
            val layoutResult =
                LayoutResult(
                    engineId = dev.kuml.layout.LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(300f, 200f),
                    nodes =
                        mapOf(
                            NodeId("inner-task") to
                                NodeLayout(
                                    bounds = Rect(Point(60f, 60f), Size(120f, 60f)),
                                ),
                        ),
                    edges = emptyMap(),
                    groups =
                        mapOf(
                            dev.kuml.layout.GroupId("sp-expanded") to
                                dev.kuml.layout.GroupLayout(
                                    bounds = Rect(Point(20f, 20f), Size(260f, 160f)),
                                ),
                        ),
                )
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, layoutResult, PlainTheme())

            // SubProcess outer frame must be present
            svg shouldContain "<rect"
            svg shouldContain "Expanded SP"
            // Child task must also be present
            svg shouldContain "Inner Task"
            // The frame rect must appear before the child task text in document order
            // (groups-first ensures the frame is painted as background)
            val framePos = svg.indexOf("Expanded SP")
            val childPos = svg.indexOf("Inner Task")
            assert(framePos < childPos) {
                "SubProcess frame label must appear before child task in SVG (groups-first z-order)"
            }
        }

        // ── Gateway-Tests ─────────────────────────────────────────────────────────

        test("Gateway EXCLUSIVE: SVG enthält Raute + X") {
            val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.EXCLUSIVE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, gatewayLayout("gw1"), PlainTheme())

            svg shouldContain "<polygon"
            // X: zwei gekreuzte Linien
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "EXCLUSIVE gateway must have at least 2 lines for X, found $lineCount" }
        }

        test("Gateway PARALLEL: SVG enthält Raute + Kreuz (+)") {
            val gw = BpmnGateway(id = "gw2", gatewayType = GatewayType.PARALLEL)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, gatewayLayout("gw2"), PlainTheme())

            svg shouldContain "<polygon"
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "PARALLEL gateway must have at least 2 lines for +, found $lineCount" }
        }

        test("Gateway INCLUSIVE: SVG enthält Raute + leerer Kreis") {
            val gw = BpmnGateway(id = "gw3", gatewayType = GatewayType.INCLUSIVE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, gatewayLayout("gw3"), PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "<circle"
            svg shouldContain "fill=\"none\""
        }

        test("Gateway EVENT_BASED: SVG enthält Raute + Doppelkreis + Pentagon") {
            val gw = BpmnGateway(id = "gw4", gatewayType = GatewayType.EVENT_BASED)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, gatewayLayout("gw4"), PlainTheme())

            svg shouldContain "<polygon"
            // Mindestens 2 Kreise (Doppelkreis) plus das Pentagon-Polygon (= 2 polygons)
            val circleCount = svg.split("<circle").size - 1
            assert(circleCount >= 2) { "EVENT_BASED gateway must have at least 2 circles, found $circleCount" }
        }

        test("Gateway COMPLEX: SVG enthält Raute + Asterisk") {
            val gw = BpmnGateway(id = "gw5", gatewayType = GatewayType.COMPLEX)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, gatewayLayout("gw5"), PlainTheme())

            svg shouldContain "<polygon"
            // Asterisk: 3 Linien
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "COMPLEX gateway must have at least 3 lines for asterisk, found $lineCount" }
        }

        // ── Task-Tests ────────────────────────────────────────────────────────────

        test("BpmnTask NONE: SVG enthält abgerundetes Rechteck + Label") {
            val task = BpmnTask(id = "t1", name = "Review Order", taskType = TaskType.NONE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t1"), PlainTheme())

            svg shouldContain "<rect"
            svg shouldContain "rx=\"6\""
            svg shouldContain "Review Order"
        }

        test("BpmnTask: SVG enthält transparentes Pulse-Overlay-Rect fuer SMIL-Animation") {
            // Das Overlay-Rect ist das SMIL-Animationsziel fuer stroke-width-Pulse.
            // Es muss fill="none", stroke-width="0" und pointer-events="none" tragen,
            // damit es im Ruhezustand unsichtbar ist und Klicks durchlaesst.
            val task = BpmnTask(id = "t1", name = "Tu was", taskType = TaskType.NONE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t1"), PlainTheme())

            svg shouldContain "id=\"t1-box-pulse\""
            svg shouldContain "stroke-width=\"0\""
            svg shouldContain "pointer-events=\"none\""
        }

        test("BpmnTask SERVICE: SVG enthält Rechteck + Service-Symbol") {
            val task = BpmnTask(id = "t2", name = "Call API", taskType = TaskType.SERVICE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t2"), PlainTheme())

            svg shouldContain "<rect"
            svg shouldContain "Call API"
            // Service-Icon (Zahnrad ⚙)
            svg shouldContain "⚙"
        }

        test("BpmnTask mit StandardLoop: SVG enthält Loop-Marker") {
            val task =
                BpmnTask(
                    id = "t3",
                    name = "Retry",
                    loopCharacteristics = StandardLoop(),
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t3"), PlainTheme())

            // Loop-Marker: Pfad-Kreis ↻
            svg shouldContain "<path"
            svg shouldContain "A 6,6 0 1,1"
        }

        test("BpmnTask mit MultiInstanceLoop parallel: SVG enthält vertikale Striche") {
            val task =
                BpmnTask(
                    id = "t4",
                    name = "Process Items",
                    loopCharacteristics = MultiInstanceLoop(sequential = false),
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t4"), PlainTheme())

            // Drei vertikale Striche ‖ als line-Elemente
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "Parallel MI must have at least 3 vertical lines, found $lineCount" }
        }

        test("BpmnTask mit MultiInstanceLoop sequential: SVG enthält horizontale Striche") {
            val task =
                BpmnTask(
                    id = "t5",
                    name = "Process Items",
                    loopCharacteristics = MultiInstanceLoop(sequential = true),
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("t5"), PlainTheme())

            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "Sequential MI must have at least 3 horizontal lines, found $lineCount" }
        }

        // ── SubProcess-Tests ──────────────────────────────────────────────────────

        test("BpmnSubProcess collapsed: SVG enthält + Symbol") {
            val sp = BpmnSubProcess(id = "sp1", name = "Sub-Process", expanded = false)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(sp))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("sp1"), PlainTheme())

            svg shouldContain "<rect"
            // + Symbol: kleines rect + zwei lines
            svg shouldContain "width=\"14\""
            svg shouldContain "height=\"14\""
        }

        test("BpmnSubProcess transactional: SVG enthält doppelten Rahmen") {
            val sp = BpmnSubProcess(id = "sp2", name = "TX", transactional = true, expanded = true)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(sp))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("sp2"), PlainTheme())

            // Äußeres Rect + inneres Rect (doppelter Rahmen)
            val rectCount = svg.split("<rect").size - 1
            assert(rectCount >= 2) { "Transactional sub-process must have at least 2 rects, found $rectCount" }
        }

        // ── CallActivity-Tests ────────────────────────────────────────────────────

        test("BpmnCallActivity: SVG enthält dicken Rand (stroke-width=3)") {
            val ca = BpmnCallActivity(id = "ca1", name = "Call OrderService")
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(ca))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("ca1"), PlainTheme())

            svg shouldContain "stroke-width=\"3\""
            svg shouldContain "Call OrderService"
        }

        // ── DataObject-Tests ──────────────────────────────────────────────────────

        test("DataObject: SVG enthält Dokumenten-Symbol mit geknickter Ecke") {
            val data = BpmnDataObject(id = "d1", name = "Order Data")
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(data))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("d1", 40f, 55f), PlainTheme())

            // Dokumenten-Pfad mit M/L
            svg shouldContain "<path"
            svg shouldContain "Order Data"
            // Fold-Polyline
            svg shouldContain "<polyline"
        }

        test("DataObject collection=true: SVG enthält Collection-Marker") {
            val data = BpmnDataObject(id = "d2", name = "Orders", collection = true)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(data))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, singleNodeLayout("d2", 40f, 55f), PlainTheme())

            // Collection-Marker: 3 vertikale Striche
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "Collection data object must have at least 3 lines, found $lineCount" }
        }

        // ── SequenceFlow-Tests ────────────────────────────────────────────────────

        test("SequenceFlow: SVG enthält Pfeil mit gefülltem Pfeilkopf") {
            val startEvent = BpmnEvent(id = "se1", position = EventPosition.START, outgoing = listOf("sf1"))
            val endEvent =
                BpmnEvent(id = "ee1", position = EventPosition.END, behaviour = EventBehaviour.THROWING, incoming = listOf("sf1"))
            val flow = SequenceFlow(id = "sf1", sourceRef = "se1", targetRef = "ee1")
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.BPMN_PROCESS,
                    elements = listOf(startEvent, endEvent, flow),
                )
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, twoNodeLayout("se1", "ee1", "sf1"), PlainTheme())

            // Pfeilkopf-Marker
            svg shouldContain "<marker"
            // Gefülltes Dreieck als Pfeilkopf
            svg shouldContain "<polygon points=\"0,0 8,3 0,6\""
            // Pfad für die Linie
            svg shouldContain "marker-end="
        }

        test("SequenceFlow isDefault=true: SVG enthält Schrägstrich") {
            val startEvent = BpmnEvent(id = "se2", position = EventPosition.START, outgoing = listOf("sf2"))
            val endEvent =
                BpmnEvent(id = "ee2", position = EventPosition.END, behaviour = EventBehaviour.THROWING, incoming = listOf("sf2"))
            val flow = SequenceFlow(id = "sf2", sourceRef = "se2", targetRef = "ee2", isDefault = true)
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.BPMN_PROCESS,
                    elements = listOf(startEvent, endEvent, flow),
                )
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, twoNodeLayout("se2", "ee2", "sf2"), PlainTheme())

            // Default-Flow: Schrägstrich als <line>
            svg shouldContain "<line"
        }

        test("SequenceFlow mit name: SVG enthält Label-Text") {
            val startEvent = BpmnEvent(id = "se3", position = EventPosition.START, outgoing = listOf("sf3"))
            val endEvent =
                BpmnEvent(id = "ee3", position = EventPosition.END, behaviour = EventBehaviour.THROWING, incoming = listOf("sf3"))
            val flow = SequenceFlow(id = "sf3", sourceRef = "se3", targetRef = "ee3", name = "Approved")
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.BPMN_PROCESS,
                    elements = listOf(startEvent, endEvent, flow),
                )
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, twoNodeLayout("se3", "ee3", "sf3"), PlainTheme())

            svg shouldContain "Approved"
        }

        // ── End-to-End-Test ───────────────────────────────────────────────────────

        test("End-to-End: BpmnModel DSL -> Layout -> SVG, alle Knoten und Flows sichtbar") {
            val startEvent =
                BpmnEvent(
                    id = "start",
                    name = "Order Received",
                    position = EventPosition.START,
                    definition = EventDefinition.MESSAGE,
                    outgoing = listOf("flow1"),
                )
            val reviewTask =
                BpmnTask(
                    id = "review",
                    name = "Review Order",
                    taskType = TaskType.USER,
                    incoming = listOf("flow1"),
                    outgoing = listOf("flow2"),
                )
            val gateway =
                BpmnGateway(
                    id = "gw",
                    name = "Approved?",
                    gatewayType = GatewayType.EXCLUSIVE,
                    incoming = listOf("flow2"),
                    outgoing = listOf("flow3", "flow4"),
                )
            val processTask =
                BpmnTask(
                    id = "process",
                    name = "Process Order",
                    taskType = TaskType.SERVICE,
                    incoming = listOf("flow3"),
                    outgoing = listOf("flow5"),
                )
            val rejectTask =
                BpmnTask(
                    id = "reject",
                    name = "Reject Order",
                    incoming = listOf("flow4"),
                    outgoing = listOf("flow6"),
                )
            val endEvent =
                BpmnEvent(
                    id = "end",
                    name = "Order Done",
                    position = EventPosition.END,
                    definition = EventDefinition.NONE,
                    behaviour = EventBehaviour.THROWING,
                    incoming = listOf("flow5", "flow6"),
                )
            val flows =
                listOf(
                    SequenceFlow(id = "flow1", sourceRef = "start", targetRef = "review"),
                    SequenceFlow(id = "flow2", sourceRef = "review", targetRef = "gw"),
                    SequenceFlow(id = "flow3", sourceRef = "gw", targetRef = "process", name = "Yes"),
                    SequenceFlow(id = "flow4", sourceRef = "gw", targetRef = "reject", name = "No", isDefault = true),
                    SequenceFlow(id = "flow5", sourceRef = "process", targetRef = "end"),
                    SequenceFlow(id = "flow6", sourceRef = "reject", targetRef = "end"),
                )

            val diagram =
                KumlDiagram(
                    name = "Order Process",
                    type = DiagramType.BPMN_PROCESS,
                    elements = listOf(startEvent, reviewTask, gateway, processTask, rejectTask, endEvent) + flows,
                )

            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 200f),
                    nodes =
                        mapOf(
                            NodeId("start") to NodeLayout(bounds = Rect(Point(10f, 80f), Size(36f, 36f))),
                            NodeId("review") to NodeLayout(bounds = Rect(Point(80f, 70f), Size(120f, 60f))),
                            NodeId("gw") to NodeLayout(bounds = Rect(Point(240f, 75f), Size(50f, 50f))),
                            NodeId("process") to NodeLayout(bounds = Rect(Point(330f, 40f), Size(120f, 60f))),
                            NodeId("reject") to NodeLayout(bounds = Rect(Point(330f, 120f), Size(120f, 60f))),
                            NodeId("end") to NodeLayout(bounds = Rect(Point(490f, 80f), Size(36f, 36f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("flow1") to EdgeRoute.Direct(Point(46f, 98f), Point(80f, 98f)),
                            EdgeId("flow2") to EdgeRoute.Direct(Point(200f, 98f), Point(240f, 98f)),
                            EdgeId("flow3") to EdgeRoute.Direct(Point(290f, 90f), Point(330f, 65f)),
                            EdgeId("flow4") to EdgeRoute.Direct(Point(290f, 105f), Point(330f, 145f)),
                            EdgeId("flow5") to EdgeRoute.Direct(Point(450f, 65f), Point(490f, 95f)),
                            EdgeId("flow6") to EdgeRoute.Direct(Point(450f, 145f), Point(490f, 100f)),
                        ),
                    groups = emptyMap(),
                )

            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram, layoutResult, PlainTheme())

            // Alle Knoten müssen im SVG vorhanden sein
            svg shouldContain "Order Received"
            svg shouldContain "Review Order"
            svg shouldContain "Approved?"
            svg shouldContain "Process Order"
            svg shouldContain "Reject Order"
            svg shouldContain "Order Done"

            // SequenceFlows: Pfeile + Labels
            svg shouldContain "Yes"
            svg shouldContain "No"

            // Strukturprüfung: Kreise, Raute, Rechtecke
            svg shouldContain "<circle" // Events
            svg shouldContain "<polygon" // Gateway
            svg shouldContain "<rect" // Tasks

            // Mindestens ein Pfeilkopf-Marker
            svg shouldContain "<marker"
        }
    })
