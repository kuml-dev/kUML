package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 130: [ErmEdgeLabelPlan.compute] accepts fixed obstacles (labels a renderer lays out itself). */
class ErmEdgeLabelPlanFixedObstacleTest :
    StringSpec({
        val routes = mapOf("r1" to (EdgeRoute.Direct(source = Point(x = 100f, y = 100f), target = Point(x = 100f, y = 400f)) as EdgeRoute))
        val nodes =
            mapOf(
                "a" to Rect(origin = Point(x = 40f, y = 20f), size = Size(width = 120f, height = 80f)),
                "b" to Rect(origin = Point(x = 40f, y = 400f), size = Size(width = 120f, height = 80f)),
            )
        val labels = mapOf("r1" to ErmEdgeLabelSpec(name = "places", sourceRole = null, targetRole = null))

        fun overlaps(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        "no fixed obstacles: same result as the default" {
            val plain = ErmEdgeLabelPlan.compute(routes = routes, nodes = nodes, labels = labels)
            val explicit = ErmEdgeLabelPlan.compute(routes = routes, nodes = nodes, labels = labels, fixedObstacles = emptyList())
            explicit shouldBe plain
            plain.getValue("r1").getValue(ErmLabelKeys.CENTER) shouldBe explicit.getValue("r1").getValue(ErmLabelKeys.CENTER)
        }

        "a fixed rectangle over the preferred spot moves the label away" {
            val free = ErmEdgeLabelPlan.compute(routes = routes, nodes = nodes, labels = labels)
            val preferred = free.getValue("r1").getValue(ErmLabelKeys.CENTER).rect
            val fixed =
                Rect(
                    origin = Point(x = preferred.origin.x - 2f, y = preferred.origin.y - 2f),
                    size =
                        Size(
                            width =
                                preferred.size.width + 4f,
                            height = preferred.size.height + 4f,
                        ),
                )
            val moved = ErmEdgeLabelPlan.compute(routes = routes, nodes = nodes, labels = labels, fixedObstacles = listOf(fixed))
            val rect = moved.getValue("r1").getValue(ErmLabelKeys.CENTER).rect
            overlaps(rect, fixed) shouldBe false
            (rect != preferred) shouldBe true
        }
    })
