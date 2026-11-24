package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.INIT_LABEL_ARROWHEAD_GAP_PX
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import kotlin.math.hypot
import kotlin.math.max

/** PAL wave I (2): the label of an initial transition keeps its distance from the arrowhead. */
class StmInitLabelArrowheadTest :
    FunSpec(body = {
        timeout = 30_000L
        val arrowLen = dev.kuml.layout.bridge.port.PortEdgeClamp.ARROW_LEN_PX

        fun distance(
            p: Point,
            r: Rect,
        ): Float {
            val dx = max(max(r.origin.x - p.x, 0f), p.x - (r.origin.x + r.size.width))
            val dy = max(max(r.origin.y - p.y, 0f), p.y - (r.origin.y + r.size.height))
            return hypot(dx, dy)
        }

        test("an init label stays at least 16 px from the arrowhead polygon") {
            val initial = Rect(origin = Point(x = 100f, y = 20f), size = Size(width = 24f, height = 24f))
            val target = Rect(origin = Point(x = 40f, y = 80f), size = Size(width = 150f, height = 40f))
            val tip = Point(x = 112f, y = 80f)
            val edges = mapOf("init" to EdgeRoute.Direct(source = Point(x = 112f, y = 44f), target = tip))
            val plan =
                Sysml2DiagramLabelPlan.planStmLabels(
                    edges = edges,
                    nodes = mapOf("start" to initial, "a" to target),
                    composites = emptyList(),
                    labels = listOf(StmLabelSpec(id = "init", lines = listOf("init"), size = Size(width = 30f, height = 16f))),
                    frameTop = 0f,
                )
            val label = plan.labels.getValue("init").rect
            // Arrowhead polygon: tip and the two base corners (length 12, half width 5 as drawn).
            val polygon = listOf(tip, Point(x = tip.x - 5f, y = tip.y - arrowLen), Point(x = tip.x + 5f, y = tip.y - arrowLen))
            val gap = polygon.minOf { distance(p = it, r = label) }
            gap shouldBeGreaterThanOrEqual INIT_LABEL_ARROWHEAD_GAP_PX
        }
    })
