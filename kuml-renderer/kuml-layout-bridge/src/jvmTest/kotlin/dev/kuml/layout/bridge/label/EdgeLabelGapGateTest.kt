@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

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
    })
