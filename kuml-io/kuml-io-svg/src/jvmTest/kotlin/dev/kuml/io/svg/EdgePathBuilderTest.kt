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
                    source = Point(x = 10f, y = 20f),
                    target = Point(x = 100f, y = 80f),
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // Direct route must start with M and contain L
            pathData shouldContain "M "
            pathData shouldContain " L "
        }

        test("EdgePathBuilder generates path with rounded corners for OrthogonalRounded") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 10f, y = 20f),
                    target = Point(x = 100f, y = 80f),
                    waypoints = listOf(Point(x = 10f, y = 80f)),
                    cornerRadiusPx = 4f,
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // OrthogonalRounded must contain Arc command when radius > 0
            pathData shouldContain "A "
        }

        test("EdgePathBuilder generates cubic bezier for Bezier route") {
            val route =
                EdgeRoute.Bezier(
                    source = Point(x = 10f, y = 20f),
                    target = Point(x = 100f, y = 80f),
                    controlPoints = listOf(Point(x = 30f, y = 10f), Point(x = 80f, y = 90f)),
                )
            val pathData = EdgePathBuilder.buildPathData(route)

            // Bezier must contain cubic Bézier command C
            pathData shouldContain " C "
        }
    })
