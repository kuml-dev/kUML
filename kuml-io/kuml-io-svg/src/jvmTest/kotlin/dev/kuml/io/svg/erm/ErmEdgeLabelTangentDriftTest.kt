package dev.kuml.io.svg.erm

import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.bridge.label.ErmEdgeLabelPlan
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The ERM label planner (in the layout bridge) keeps a copy of the tangent rules of [EdgeLabelGeometry] (the SVG module depends on
 * the bridge, not the other way round). This test fails as soon as the two drift apart.
 */
class ErmEdgeLabelTangentDriftTest :
    StringSpec({
        val routes: Map<String, EdgeRoute> =
            mapOf(
                "direct" to EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 30f, y = 40f)),
                "orthogonal" to
                    EdgeRoute.OrthogonalRounded(
                        source = Point(x = 0f, y = 0f),
                        target = Point(x = 100f, y = 80f),
                        waypoints = listOf(Point(x = 0f, y = 40f), Point(x = 100f, y = 40f)),
                        cornerRadiusPx = 4f,
                    ),
                "tree" to
                    EdgeRoute.TreeRounded(
                        source = Point(x = 10f, y = 10f),
                        target = Point(x = 90f, y = 10f),
                        waypoints = listOf(Point(x = 10f, y = 50f), Point(x = 90f, y = 50f)),
                        cornerRadiusPx = 4f,
                    ),
                "quadratic" to
                    EdgeRoute.Bezier(
                        source = Point(x = 0f, y = 0f),
                        target = Point(x = 100f, y = 0f),
                        controlPoints = listOf(Point(x = 50f, y = 60f)),
                    ),
                "cubic" to
                    EdgeRoute.Bezier(
                        source = Point(x = 0f, y = 0f),
                        target = Point(x = 100f, y = 100f),
                        controlPoints = listOf(Point(x = 0f, y = 60f), Point(x = 100f, y = 40f)),
                    ),
                "zero-length" to EdgeRoute.Direct(source = Point(x = 5f, y = 5f), target = Point(x = 5f, y = 5f)),
            )
        for ((name, route) in routes) {
            "source tangent matches EdgeLabelGeometry for $name" {
                ErmEdgeLabelPlan.sourceTangent(route) shouldBe EdgeLabelGeometry.sourceSegmentTangent(route)
            }
            "target tangent matches EdgeLabelGeometry for $name" {
                ErmEdgeLabelPlan.targetTangent(route) shouldBe EdgeLabelGeometry.targetSegmentTangent(route)
            }
        }
        "zero-length route falls back to (1, 0)" {
            ErmEdgeLabelPlan.sourceTangent(routes.getValue("zero-length")) shouldBe (1f to 0f)
        }
    })
