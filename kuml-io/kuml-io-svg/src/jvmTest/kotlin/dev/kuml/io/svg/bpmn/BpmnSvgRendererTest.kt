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
                canvas = Size(width = w + 20f, height = h + 20f),
                nodes =
                    mapOf(
                        NodeId(id) to NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = w, height = h))),
                    ),
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
                canvas = Size(width = 300f, height = 100f),
                nodes =
                    mapOf(
                        NodeId(id1) to NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 30f), size = Size(width = 36f, height = 36f))),
                        NodeId(id2) to NodeLayout(bounds = Rect(origin = Point(x = 200f, y = 30f), size = Size(width = 36f, height = 36f))),
                    ),
                edges =
                    mapOf(
                        EdgeId(edgeId) to
                            EdgeRoute.Direct(
                                source = Point(x = 46f, y = 48f),
                                target = Point(x = 200f, y = 48f),
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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e2"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e3"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e4"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e5"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e6"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("e7"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("et1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("ee1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("ee2"), theme = PlainTheme())

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
                    .toSvg(diagram = catchingDiagram, layoutResult = eventLayout("es_c"), theme = PlainTheme())
            val throwingSvg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = throwingDiagram, layoutResult = eventLayout("es_t"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("ec1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("ecd1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("el1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("ecn1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("em1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = eventLayout("epm1"), theme = PlainTheme())

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
                    canvas = Size(width = 300f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("inner-task") to
                                NodeLayout(
                                    bounds = Rect(origin = Point(x = 60f, y = 60f), size = Size(width = 120f, height = 60f)),
                                ),
                        ),
                    edges = emptyMap(),
                    groups =
                        mapOf(
                            dev.kuml.layout.GroupId("sp-expanded") to
                                dev.kuml.layout.GroupLayout(
                                    bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 260f, height = 160f)),
                                ),
                        ),
                )
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = layoutResult, theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = gatewayLayout("gw1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = gatewayLayout("gw2"), theme = PlainTheme())

            svg shouldContain "<polygon"
            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 2) { "PARALLEL gateway must have at least 2 lines for +, found $lineCount" }
        }

        test("Gateway INCLUSIVE: SVG enthält Raute + leerer Kreis") {
            val gw = BpmnGateway(id = "gw3", gatewayType = GatewayType.INCLUSIVE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = gatewayLayout("gw3"), theme = PlainTheme())

            svg shouldContain "<polygon"
            svg shouldContain "<circle"
            svg shouldContain "fill=\"none\""
        }

        test("Gateway EVENT_BASED: SVG enthält Raute + Doppelkreis + Pentagon") {
            val gw = BpmnGateway(id = "gw4", gatewayType = GatewayType.EVENT_BASED)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(gw))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = gatewayLayout("gw4"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = gatewayLayout("gw5"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t1"), theme = PlainTheme())

            svg shouldContain "id=\"t1-box-pulse\""
            svg shouldContain "stroke-width=\"0\""
            svg shouldContain "pointer-events=\"none\""
        }

        test("BpmnTask SERVICE: SVG enthält Rechteck + Service-Symbol") {
            val task = BpmnTask(id = "t2", name = "Call API", taskType = TaskType.SERVICE)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(task))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t2"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t3"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t4"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("t5"), theme = PlainTheme())

            val lineCount = svg.split("<line").size - 1
            assert(lineCount >= 3) { "Sequential MI must have at least 3 horizontal lines, found $lineCount" }
        }

        // ── SubProcess-Tests ──────────────────────────────────────────────────────

        test("BpmnSubProcess collapsed: SVG enthält + Symbol") {
            val sp = BpmnSubProcess(id = "sp1", name = "Sub-Process", expanded = false)
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(sp))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("sp1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("sp2"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("ca1"), theme = PlainTheme())

            svg shouldContain "stroke-width=\"3\""
            svg shouldContain "Call OrderService"
        }

        // ── DataObject-Tests ──────────────────────────────────────────────────────

        test("DataObject: SVG enthält Dokumenten-Symbol mit geknickter Ecke") {
            val data = BpmnDataObject(id = "d1", name = "Order Data")
            val diagram = KumlDiagram(name = "D", type = DiagramType.BPMN_PROCESS, elements = listOf(data))
            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("d1", 40f, 55f), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = singleNodeLayout("d2", 40f, 55f), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = twoNodeLayout("se1", "ee1", "sf1"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = twoNodeLayout("se2", "ee2", "sf2"), theme = PlainTheme())

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
                    .toSvg(diagram = diagram, layoutResult = twoNodeLayout("se3", "ee3", "sf3"), theme = PlainTheme())

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
                    canvas = Size(width = 600f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("start") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 80f), size = Size(width = 36f, height = 36f))),
                            NodeId("review") to
                                NodeLayout(bounds = Rect(origin = Point(x = 80f, y = 70f), size = Size(width = 120f, height = 60f))),
                            NodeId("gw") to
                                NodeLayout(bounds = Rect(origin = Point(x = 240f, y = 75f), size = Size(width = 50f, height = 50f))),
                            NodeId("process") to
                                NodeLayout(bounds = Rect(origin = Point(x = 330f, y = 40f), size = Size(width = 120f, height = 60f))),
                            NodeId("reject") to
                                NodeLayout(bounds = Rect(origin = Point(x = 330f, y = 120f), size = Size(width = 120f, height = 60f))),
                            NodeId("end") to
                                NodeLayout(bounds = Rect(origin = Point(x = 490f, y = 80f), size = Size(width = 36f, height = 36f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("flow1") to EdgeRoute.Direct(source = Point(x = 46f, y = 98f), target = Point(x = 80f, y = 98f)),
                            EdgeId("flow2") to EdgeRoute.Direct(source = Point(x = 200f, y = 98f), target = Point(x = 240f, y = 98f)),
                            EdgeId("flow3") to EdgeRoute.Direct(source = Point(x = 290f, y = 90f), target = Point(x = 330f, y = 65f)),
                            EdgeId("flow4") to EdgeRoute.Direct(source = Point(x = 290f, y = 105f), target = Point(x = 330f, y = 145f)),
                            EdgeId("flow5") to EdgeRoute.Direct(source = Point(x = 450f, y = 65f), target = Point(x = 490f, y = 95f)),
                            EdgeId("flow6") to EdgeRoute.Direct(source = Point(x = 450f, y = 145f), target = Point(x = 490f, y = 100f)),
                        ),
                    groups = emptyMap(),
                )

            val svg =
                dev.kuml.io.svg.KumlSvgRenderer
                    .toSvg(diagram = diagram, layoutResult = layoutResult, theme = PlainTheme())

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
