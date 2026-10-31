@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * PAL 2C-b1: font-independent routing invariants of IBDs (reference case "Steuerung" with the parts `logik`,
 * `sensor` and `antrieb` and the owner port `ausgang`). Only geometry of the bridge result is checked.
 */
class Sysml2IbdRoutingInvariantTest :
    FunSpec({
        fun steuerung(sensorFirst: Boolean): Sysml2Model =
            sysml2Model(name = "Steuerung") {
                portDef(name = "Signal")
                connectionDef(name = "Leitung")
                partDef(name = "Logik") { port(name = "befehl", typeId = "Signal", direction = PortDirection.Out) }
                partDef(name = "Sensor") { port(name = "messung", typeId = "Signal", direction = PortDirection.Out) }
                partDef(name = "Antrieb") {
                    port(name = "ein", typeId = "Signal", direction = PortDirection.In)
                    port(name = "raus", typeId = "Signal", direction = PortDirection.Out)
                }
                partDef(name = "Steuerung") {
                    port(name = "ausgang", typeId = "Signal", direction = PortDirection.Out)
                    if (sensorFirst) {
                        part(name = "sensor", typeId = "Sensor")
                        part(name = "antrieb", typeId = "Antrieb")
                        part(name = "logik", typeId = "Logik")
                    } else {
                        part(name = "logik", typeId = "Logik")
                        part(name = "antrieb", typeId = "Antrieb")
                        part(name = "sensor", typeId = "Sensor")
                    }
                    connect(name = "w1", typeId = "Leitung", sourceEndId = "logik.befehl", targetEndId = "antrieb.ein")
                    connect(name = "w2", typeId = "Leitung", sourceEndId = "antrieb.raus", targetEndId = "Steuerung::ausgang")
                }
            }
        val ibd = IbdDiagram(name = "s", ownerId = "Steuerung")

        fun r(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        // sensor sits exactly between logik and antrieb on the same row: the straight line logik -> antrieb is blocked.
        val boxes =
            mapOf(
                "Steuerung::logik" to r(100f, 200f, 180f, 80f),
                "Steuerung::sensor" to r(380f, 160f, 180f, 160f),
                "Steuerung::antrieb" to r(660f, 200f, 180f, 80f),
            )

        fun layout(model: Sysml2Model): LayoutResult {
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes =
                g.nodes.associate { n ->
                    n.id to NodeLayout(bounds = boxes[n.id.value] ?: r(0f, 0f, n.intrinsicSize.width, n.intrinsicSize.height))
                }
            val ends =
                mapOf(
                    "conn:Steuerung::w1" to (Point(x = 280f, y = 240f) to Point(x = 660f, y = 240f)),
                    "conn:Steuerung::w2" to (Point(x = 840f, y = 240f) to Point(x = 0f, y = 0f)),
                )
            val edges =
                g.edges.associate { e ->
                    val (s, t) = ends.getValue(e.id.value)
                    e.id to EdgeRoute.Direct(source = s, target = t) as EdgeRoute
                }
            return LayoutResult(
                engineId = LayoutEngineId("t"),
                seed = 1L,
                canvas = Size(width = 1000f, height = 500f),
                nodes = nodes,
                edges = edges,
                groups = emptyMap(),
            )
        }

        fun pts(route: EdgeRoute): List<Point> =
            when (route) {
                is EdgeRoute.Direct -> listOf(route.source, route.target)
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.Bezier -> listOf(route.source) + route.controlPoints + listOf(route.target)
            }

        fun frameOf(lr: LayoutResult) = lr.groups.getValue(Sysml2LayoutBridge.ibdFrameGroupId("Steuerung")).bounds

        fun ownerSide(lr: LayoutResult): Sysml2LayoutBridge.IbdSide {
            val f = frameOf(lr)
            val b = lr.nodes.getValue(NodeId("Steuerung::ausgang")).bounds
            val cx = b.origin.x + b.size.width / 2f
            val cy = b.origin.y + b.size.height / 2f
            return when {
                abs(cy - f.origin.y) < 0.5f -> Sysml2LayoutBridge.IbdSide.Top
                abs(cy - (f.origin.y + f.size.height)) < 0.5f -> Sysml2LayoutBridge.IbdSide.Bottom
                abs(cx - f.origin.x) < 0.5f -> Sysml2LayoutBridge.IbdSide.Left
                abs(cx - (f.origin.x + f.size.width)) < 0.5f -> Sysml2LayoutBridge.IbdSide.Right
                else -> error("owner port not on the frame")
            }
        }

        val endsOf =
            mapOf(
                "conn:Steuerung::w1" to setOf("Steuerung::logik", "Steuerung::antrieb"),
                "conn:Steuerung::w2" to setOf("Steuerung::antrieb"),
            )

        test("framed: no segment of any edge enters the interior of a foreign part") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(false), diagram = ibd, layoutResult = layout(steuerung(false)))
            for ((eid, route) in lr.edges) {
                val foreign =
                    lr.nodes.filter { (id, _) -> id.value.startsWith("Steuerung::") && id.value != "Steuerung::ausgang" }.filterKeys {
                        it.value !in endsOf.getValue(eid.value)
                    }
                val p = pts(route)
                for (nl in foreign.values) {
                    for (i in 0 until p.size - 1) {
                        IbdObstacleRouter.segmentHitsInterior(p[i], p[i + 1], nl.bounds, shrink = 0.5f) shouldBe false
                    }
                }
            }
        }

        test("framed: swapping the part declaration order keeps the side of the owner port") {
            val a = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(false), diagram = ibd, layoutResult = layout(steuerung(false)))
            val b = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(true), diagram = ibd, layoutResult = layout(steuerung(true)))
            ownerSide(a) shouldBe ownerSide(b)
        }

        test("framed: the layout does not depend on the part declaration order") {
            val a = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(false), diagram = ibd, layoutResult = layout(steuerung(false)))
            val b = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(true), diagram = ibd, layoutResult = layout(steuerung(true)))
            a.nodes.mapKeys { it.key.value }.toSortedMap() shouldBe b.nodes.mapKeys { it.key.value }.toSortedMap()
            a.edges.mapKeys { it.key.value }.toSortedMap() shouldBe b.edges.mapKeys { it.key.value }.toSortedMap()
        }

        test("framed: part-port and owner-port centres lie on the box border and the frame (+-0.5 px)") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = steuerung(false), diagram = ibd, layoutResult = layout(steuerung(false)))
            for ((id, nl) in lr.nodes) {
                for ((pid, local) in nl.ports) {
                    val onBorder =
                        abs(local.x) <= 0.5f ||
                            abs(local.y) <= 0.5f ||
                            abs(local.x - nl.bounds.size.width) <= 0.5f ||
                            abs(local.y - nl.bounds.size.height) <= 0.5f
                    (id.value + "/" + pid.value to onBorder) shouldBe (id.value + "/" + pid.value to true)
                }
            }
            val f = frameOf(lr)
            val b = lr.nodes.getValue(NodeId("Steuerung::ausgang")).bounds
            val cx = b.origin.x + b.size.width / 2f
            val cy = b.origin.y + b.size.height / 2f
            val onFrame =
                abs(cy - f.origin.y) <= 0.5f ||
                    abs(cy - (f.origin.y + f.size.height)) <= 0.5f ||
                    abs(cx - f.origin.x) <= 0.5f ||
                    abs(cx - (f.origin.x + f.size.width)) <= 0.5f
            onFrame shouldBe true
        }

        test("unframed: the blocked straight connection is routed around the sensor") {
            val model =
                sysml2Model(name = "S2") {
                    portDef(name = "Signal")
                    connectionDef(name = "Leitung")
                    partDef(name = "Logik") { port(name = "befehl", typeId = "Signal", direction = PortDirection.Out) }
                    partDef(name = "Sensor")
                    partDef(name = "Antrieb") { port(name = "ein", typeId = "Signal", direction = PortDirection.In) }
                    partDef(name = "Steuerung") {
                        part(name = "logik", typeId = "Logik")
                        part(name = "sensor", typeId = "Sensor")
                        part(name = "antrieb", typeId = "Antrieb")
                        connect(name = "w1", typeId = "Leitung", sourceEndId = "logik.befehl", targetEndId = "antrieb.ein")
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes = g.nodes.associate { n -> n.id to NodeLayout(bounds = boxes.getValue(n.id.value)) }
            val edges =
                g.edges.associate {
                    it.id to EdgeRoute.Direct(source = Point(x = 280f, y = 240f), target = Point(x = 660f, y = 240f)) as EdgeRoute
                }
            val base =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 1000f, height = 500f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            val lr = Sysml2LayoutBridge.enrichIbdPortPositions(model = model, diagram = ibd, layoutResult = base)
            val p = pts(lr.edges.getValue(dev.kuml.layout.EdgeId("conn:Steuerung::w1")))
            val sensor = boxes.getValue("Steuerung::sensor")
            for (i in 0 until p.size - 1) IbdObstacleRouter.segmentHitsInterior(p[i], p[i + 1], sensor) shouldBe false
            (p.size > 2) shouldBe true
            // ports sit on the box borders
            val logik = lr.nodes.getValue(NodeId("Steuerung::logik"))
            val local = logik.ports.values.single()
            (abs(local.x - logik.bounds.size.width) <= 0.5f || abs(local.x) <= 0.5f || abs(local.y) <= 0.5f) shouldBe true
        }

        test("framed: no edge crosses a foreign port label") {
            val model =
                sysml2Model(name = "Steuerung") {
                    portDef(name = "Signal")
                    connectionDef(name = "Leitung")
                    partDef(name = "Logik") { port(name = "befehl", typeId = "Signal", direction = PortDirection.Out) }
                    partDef(name = "Sensor") {
                        port(name = "obenMessungLang", typeId = "Signal", direction = PortDirection.Out)
                        port(name = "untenMessungLang", typeId = "Signal", direction = PortDirection.Out)
                    }
                    partDef(name = "Antrieb") { port(name = "ein", typeId = "Signal", direction = PortDirection.In) }
                    partDef(name = "Steuerung") {
                        port(name = "oben", typeId = "Signal", direction = PortDirection.Out)
                        port(name = "unten", typeId = "Signal", direction = PortDirection.Out)
                        part(name = "logik", typeId = "Logik")
                        part(name = "sensor", typeId = "Sensor")
                        part(name = "antrieb", typeId = "Antrieb")
                        connect(name = "w1", typeId = "Leitung", sourceEndId = "logik.befehl", targetEndId = "antrieb.ein")
                        connect(name = "w2", typeId = "Leitung", sourceEndId = "sensor.obenMessungLang", targetEndId = "Steuerung::oben")
                        connect(name = "w3", typeId = "Leitung", sourceEndId = "sensor.untenMessungLang", targetEndId = "Steuerung::unten")
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes =
                g.nodes.associate { n ->
                    n.id to NodeLayout(bounds = boxes[n.id.value] ?: r(0f, 0f, n.intrinsicSize.width, n.intrinsicSize.height))
                }
            val edges =
                g.edges.associate { e ->
                    val (a, b) =
                        when (e.id.value) {
                            "conn:Steuerung::w1" -> Point(x = 280f, y = 240f) to Point(x = 660f, y = 240f)
                            "conn:Steuerung::w2" -> Point(x = 470f, y = 160f) to Point(x = 470f, y = 20f)
                            else -> Point(x = 470f, y = 320f) to Point(x = 470f, y = 480f)
                        }
                    e.id to EdgeRoute.Direct(source = a, target = b) as EdgeRoute
                }
            val base =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 1000f, height = 500f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = model, diagram = ibd, layoutResult = base)
            val labels = Sysml2LayoutBridge.ibdLabelRects(model = model, diagram = ibd, layout = lr)
            val own =
                mapOf(
                    "conn:Steuerung::w1" to setOf("Steuerung::logik::befehl", "Steuerung::antrieb::ein"),
                    "conn:Steuerung::w2" to setOf("Steuerung::sensor::obenMessungLang", "Steuerung::oben"),
                    "conn:Steuerung::w3" to setOf("Steuerung::sensor::untenMessungLang", "Steuerung::unten"),
                )
            (labels.size >= 5) shouldBe true
            for ((eid, route) in lr.edges) {
                val p = pts(route)
                for (lab in labels.filter { it.owner !in own.getValue(eid.value) }) {
                    for (i in 0 until p.size - 1) {
                        val hit = IbdObstacleRouter.segmentHitsInterior(p[i], p[i + 1], lab.rect, shrink = 0.5f)
                        (eid.value + " x " + lab.owner to hit) shouldBe (eid.value + " x " + lab.owner to false)
                    }
                }
            }
        }
    })
