package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class EdgePathBuilderTest :
    FunSpec({

        test("EdgePathBuilder generates straight line for Direct route") {
            val route =
                EdgeRoute.Direct(
                    source = Point(10f, 20f),
                    target = Point(100f, 80f),
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // Direct route must start with M and contain L
            pathData shouldContain "M "
            pathData shouldContain " L "
        }

        test("EdgePathBuilder generates path with rounded corners for OrthogonalRounded") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(10f, 20f),
                    target = Point(100f, 80f),
                    waypoints = listOf(Point(10f, 80f)),
                    cornerRadiusPx = 4f,
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // OrthogonalRounded must contain Arc command when radius > 0
            pathData shouldContain "A "
        }

        test("EdgePathBuilder generates cubic bezier for Bezier route") {
            val route =
                EdgeRoute.Bezier(
                    source = Point(10f, 20f),
                    target = Point(100f, 80f),
                    controlPoints = listOf(Point(30f, 10f), Point(80f, 90f)),
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // Bezier must contain cubic Bézier command C
            pathData shouldContain " C "
        }
    })
