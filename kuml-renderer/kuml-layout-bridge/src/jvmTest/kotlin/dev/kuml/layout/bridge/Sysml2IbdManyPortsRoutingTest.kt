@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression: the bridge hands every foreign port label and square to the router as an extra obstacle. With about
 * 60 ports the A* grid used to exceed its cap, the search failed and the fallback ran through foreign parts.
 */
class Sysml2IbdManyPortsRoutingTest :
    FunSpec({
        val cols = 5
        val rows = 4
        val partCount = cols * rows

        fun r(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        val model =
            sysml2Model(name = "Medium") {
                portDef(name = "Signal")
                connectionDef(name = "Leitung")
                partDef(name = "Node") {
                    port(name = "a", typeId = "Signal", direction = PortDirection.Out)
                    port(name = "b", typeId = "Signal", direction = PortDirection.In)
                    port(name = "c", typeId = "Signal", direction = PortDirection.Out)
                }
                partDef(name = "Medium") {
                    for (i in 0 until partCount) part(name = "p$i", typeId = "Node")
                    // rows: first to last part of a row, a foreign part (or more) sits in between
                    for (row in 0 until rows) {
                        connect(
                            name = "row$row",
                            typeId = "Leitung",
                            sourceEndId = "p${row * cols}.a",
                            targetEndId = "p${row * cols + cols - 1}.b",
                        )
                    }
                    connect(name = "col", typeId = "Leitung", sourceEndId = "p0.c", targetEndId = "p${partCount - cols}.b")
                }
            }
        val ibd = IbdDiagram(name = "m", ownerId = "Medium")

        fun boxOf(i: Int) = r(100f + (i % cols) * 220f, 100f + (i / cols) * 180f, 120f, 90f)

        test("medium IBD with about 20 parts and 60 ports: no edge crosses a foreign part") {
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes =
                g.nodes.associate { n ->
                    val idx =
                        n.id.value
                            .substringAfter("Medium::p")
                            .toIntOrNull()
                    n.id to NodeLayout(bounds = if (idx != null) boxOf(idx) else r(0f, 0f, n.intrinsicSize.width, n.intrinsicSize.height))
                }
            val edges =
                g.edges.associate { e ->
                    e.id to EdgeRoute.Direct(source = Point(0f, 0f), target = Point(10f, 10f)) as EdgeRoute
                }
            val lr =
                Sysml2LayoutBridge.addIbdOwnerFrame(
                    model = model,
                    diagram = ibd,
                    layoutResult =
                        LayoutResult(
                            engineId = LayoutEngineId("t"),
                            seed = 1L,
                            canvas = Size(width = 1400f, height = 900f),
                            nodes = nodes,
                            edges = edges,
                            groups = emptyMap(),
                        ),
                )
            var checked = 0
            for ((eid, route) in lr.edges) {
                val name = eid.value.substringAfter("conn:Medium::")
                val ends =
                    when (name) {
                        "col" -> setOf(0, partCount - cols)
                        else -> name.removePrefix("row").toInt().let { setOf(it * cols, it * cols + cols - 1) }
                    }.map { "Medium::p$it" }.toSet()
                val p =
                    when (route) {
                        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                        is EdgeRoute.Direct -> listOf(route.source, route.target)
                        is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                        is EdgeRoute.Bezier -> listOf(route.source) + route.controlPoints + listOf(route.target)
                    }
                for ((id, nl) in lr.nodes) {
                    if (!id.value.startsWith("Medium::p") || id.value.contains("/") || id.value in ends) continue
                    if (nl.ports.isNotEmpty() || nl.bounds.size.width > 60f) {
                        for (i in 0 until p.size - 1) {
                            val hit = IbdObstacleRouter.segmentHitsInterior(p[i], p[i + 1], nl.bounds, shrink = 0.5f)
                            (name + " crosses " + id.value to hit) shouldBe (name + " crosses " + id.value to false)
                        }
                        checked++
                    }
                }
            }
            (checked > 0) shouldBe true
        }
    })
