@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** PAL Welle X1: the state-machine edge-gap gate keeps a label 6 to 16 px from its own line and never makes a plan worse. */
class EdgeLabelGapGateTest :
    FunSpec(body = {
        val size = Size(width = 60f, height = 18f)

        fun spec(id: String) = StmLabelSpec(id = id, lines = listOf("label"), size = size)

        fun line(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ) = EdgeRoute.Direct(source = Point(x = x0, y = y0), target = Point(x = x1, y = y1))

        fun plan(
            edges: Map<String, EdgeRoute>,
            gate: Boolean,
        ) = Sysml2DiagramLabelPlan.planStmLabels(
            edges = edges,
            nodes = emptyMap(),
            composites = emptyList(),
            labels = edges.keys.sorted().map { spec(it) },
            frameTop = 0f,
            edgeGapGate = gate,
        )

        fun gapOf(
            p: Sysml2LabelPlanResult,
            id: String,
            e: EdgeRoute,
        ): Float = LabelPlacer.rectDistanceToPolyline(r = p.labels.getValue(id).rect, pts = Sysml2DiagramLabelPlan.stmPolylineOf(e))

        test("without the gate a free label sits 3 px from its line, with the gate at least 6 px") {
            val e = line(0f, 100f, 300f, 100f)
            val edges = mapOf("e" to e)
            gapOf(plan(edges, gate = false), "e", e) shouldBe 3f
            gapOf(plan(edges, gate = true), "e", e) shouldBeGreaterThanOrEqual 5.5f
        }

        test("the gated label never sits further than 16 px from its line") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f), "b" to line(0f, 140f, 300f, 140f), "c" to line(0f, 180f, 300f, 180f))
            val p = plan(edges, gate = true)
            for ((id, e) in edges) gapOf(p, id, e) shouldBeLessThanOrEqual 16.5f
        }

        test("the gate never leaves more labels outside the band or more unclean labels") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f), "b" to line(0f, 112f, 300f, 112f))
            val off = plan(edges, gate = false)
            val on = plan(edges, gate = true)
            val polys = edges.mapValues { Sysml2DiagramLabelPlan.stmPolylineOf(it.value) }
            (
                Sysml2DiagramLabelPlan.edgeGapViolations(on.labels, polys) <=
                    Sysml2DiagramLabelPlan.edgeGapViolations(off.labels, polys)
            ) shouldBe true
            (on.labels.count { !it.value.clean } <= off.labels.count { !it.value.clean }) shouldBe true
        }

        test("a diagram where the 6 px variant is not better keeps the original plan unchanged") {
            val e = mapOf("e" to line(0f, 100f, 300f, 100f))
            val off = plan(e, gate = false)
            val polys = e.mapValues { Sysml2DiagramLabelPlan.stmPolylineOf(it.value) }
            // the gate only switches when the violation count drops; a clean ungated plan has nothing to improve on
            if (Sysml2DiagramLabelPlan.edgeGapViolations(off.labels, polys) == 0) {
                plan(e, gate = true).labels shouldBe off.labels
            }
        }

        // PAL Welle Y2: local per-label repair of a label closer than 6 px to its own line.
        fun rectAt(
            x: Float,
            y: Float,
        ) = Rect(origin = Point(x = x, y = y), size = size)

        fun ctxOf(
            edges: Map<String, EdgeRoute>,
            nodes: Map<String, Rect> = emptyMap(),
        ) = Sysml2DiagramLabelPlan.stmContext(
            polys = edges.mapValues { Sysml2DiagramLabelPlan.stmPolylineOf(it.value) },
            nodes = nodes,
            composites = emptyList(),
            labels = edges.keys.sorted().map { spec(it) },
            frameTop = 0f,
        )

        fun polysOf(edges: Map<String, EdgeRoute>) = edges.mapValues { Sysml2DiagramLabelPlan.stmPolylineOf(it.value) }

        test("Y2: a close label moves into the band, an unclean label of the same diagram stays where it is") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f), "b" to line(0f, 300f, 300f, 300f))
            val close = rectAt(x = 120f, y = 103f) // 3 px under the line of a
            val unclean = rectAt(x = 120f, y = 308f) // 8 px, inside the band but unclean
            val plan0 =
                Sysml2LabelPlanResult(
                    labels =
                        mapOf(
                            "a" to PlacedLabel(rect = close, onLine = false),
                            "b" to PlacedLabel(rect = unclean, onLine = false, clean = false),
                        ),
                    lines = mapOf("a" to listOf("label"), "b" to listOf("label")),
                    unresolved = listOf("b"),
                )
            val ctx = ctxOf(edges)
            val out = StmEdgeGapRepair.repair(plan = plan0, requests = ctx.requests, base = ctx.buildIndex(), polys = polysOf(edges))
            val d = LabelPlacer.rectDistanceToPolyline(r = out.labels.getValue("a").rect, pts = polysOf(edges).getValue("a"))
            d shouldBeGreaterThanOrEqual 6f
            d shouldBeLessThanOrEqual 16f
            out.labels.getValue("a").clean shouldBe true
            out.labels.getValue("b") shouldBe plan0.labels.getValue("b")
            out.unresolved shouldBe listOf("b")
        }

        test("Y2: a label whose in-band positions all break a hard rule keeps its place and the plan is the same instance") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f))
            // nodes fill the whole band above and below the line, so no in-band position is free
            val nodes = mapOf("above" to Rect(Point(-200f, 40f), Size(700f, 59f)), "below" to Rect(Point(-200f, 101f), Size(700f, 60f)))
            val plan0 =
                Sysml2LabelPlanResult(
                    labels = mapOf("a" to PlacedLabel(rect = rectAt(x = 120f, y = 103f), onLine = false)),
                    lines = mapOf("a" to listOf("label")),
                )
            val ctx = ctxOf(edges, nodes)
            val out = StmEdgeGapRepair.repair(plan = plan0, requests = ctx.requests, base = ctx.buildIndex(), polys = polysOf(edges))
            out shouldBeSameInstanceAs plan0
        }

        test("Y2: a plan without a label closer than 6 px is returned unchanged (same instance)") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f))
            val plan0 =
                Sysml2LabelPlanResult(
                    labels = mapOf("a" to PlacedLabel(rect = rectAt(x = 120f, y = 108f), onLine = false)),
                    lines = mapOf("a" to listOf("label")),
                )
            val ctx = ctxOf(edges)
            StmEdgeGapRepair.repair(
                plan = plan0,
                requests = ctx.requests,
                base = ctx.buildIndex(),
                polys = polysOf(edges),
            ) shouldBeSameInstanceAs
                plan0
        }

        test("Y2: planning twice gives the same plan") {
            val edges = mapOf("a" to line(0f, 100f, 300f, 100f), "b" to line(0f, 112f, 300f, 112f), "c" to line(0f, 124f, 300f, 124f))
            plan(edges, gate = true) shouldBe plan(edges, gate = true)
        }
    })
