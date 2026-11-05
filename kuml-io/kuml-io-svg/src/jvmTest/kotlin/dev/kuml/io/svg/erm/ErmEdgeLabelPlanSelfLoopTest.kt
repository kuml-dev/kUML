package dev.kuml.io.svg.erm

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.LabelPlacer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Regression (spacing 1.5): a short self-loop on the right of an entity, with a foreign line running vertically less than the
 * name label's width away, has no free spot beside it; the name label must still clear the foreign line and the entity.
 */
class ErmEdgeLabelPlanSelfLoopTest :
    StringSpec({
        "the name label of a short self-loop clears a foreign line that runs close by" {
            val entity = Rect(origin = Point(x = 1148f, y = 371f), size = Size(width = 213f, height = 120f))
            val loop =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 1361f, y = 407f),
                    target = Point(x = 1361f, y = 455f),
                    waypoints = listOf(Point(x = 1393f, y = 407f), Point(x = 1393f, y = 455f)),
                    cornerRadiusPx = 0f,
                )
            val foreign =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 1449f, y = 215f),
                    target = Point(x = 1449f, y = 907f),
                    waypoints = emptyList(),
                    cornerRadiusPx = 0f,
                )
            val plan =
                ErmEdgeLabelPlan.compute(
                    routes = mapOf("rel_1" to loop, "rel_9" to foreign),
                    nodes = mapOf("entity_2" to entity),
                    labels = mapOf("rel_1" to ErmEdgeLabelSpec(name = "is parent of", sourceRole = null, targetRole = null)),
                )
            val label = plan.getValue("rel_1").getValue(ErmLabelKeys.CENTER)
            val r = label.rect
            val overlapsX = r.origin.x < entity.origin.x + entity.size.width && entity.origin.x < r.origin.x + r.size.width
            val overlapsY = r.origin.y < entity.origin.y + entity.size.height && entity.origin.y < r.origin.y + r.size.height
            (overlapsX && overlapsY) shouldBe false
            val d = LabelPlacer.rectDistanceToPolyline(r = r, pts = listOf(foreign.source, foreign.target))
            (d >= 0.5f) shouldBe true
            label.onLine shouldBe false
        }
    })
