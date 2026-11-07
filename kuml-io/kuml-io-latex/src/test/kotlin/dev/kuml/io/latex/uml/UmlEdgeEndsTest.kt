package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.UmlNavigability
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val DO = "Diamond[open, length=16pt, width=10pt]"
private const val DF = "Diamond[length=16pt, width=10pt]"
private const val OH = "Straight Barb[length=12pt, width=10pt]"

private val direct = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 0f, y = 100f))
private const val DIRECT_TAIL = "(0.000pt, -0.000pt) -- (0.000pt, -100.000pt);"

private fun draw(
    route: EdgeRoute,
    style: UmlEdgeLatexRenderer.Style,
    override: String?,
): String {
    val out = StringBuilder()
    UmlEdgeLatexRenderer.render(route = route, style = style, options = LatexRenderOptions(), out = out, arrowOverride = override)
    return out.toString().trim()
}

private val TIP_LENGTH = Regex("""length=(\d+)pt""")

private fun tipLength(side: String): Float = TIP_LENGTH.findAll(side).sumOf { it.groupValues[1].toInt() }.toFloat()

class UmlEdgeEndsTest :
    StringSpec({
        val table =
            listOf(
                Triple(AggregationKind.NONE, UmlNavigability.BOTH, "-"),
                Triple(AggregationKind.NONE, UmlNavigability.NEITHER, "-"),
                Triple(AggregationKind.NONE, UmlNavigability.TARGET_ONLY, "-{$OH}"),
                Triple(AggregationKind.NONE, UmlNavigability.SOURCE_ONLY, "{$OH}-"),
                Triple(AggregationKind.SHARED, UmlNavigability.BOTH, "{$DO}-"),
                Triple(AggregationKind.SHARED, UmlNavigability.NEITHER, "{$DO}-"),
                Triple(AggregationKind.COMPOSITE, UmlNavigability.BOTH, "{$DF}-"),
                Triple(AggregationKind.COMPOSITE, UmlNavigability.NEITHER, "{$DF}-"),
                Triple(AggregationKind.SHARED, UmlNavigability.TARGET_ONLY, "{$DO}-{$OH}"),
                Triple(AggregationKind.COMPOSITE, UmlNavigability.TARGET_ONLY, "{$DF}-{$OH}"),
                Triple(AggregationKind.SHARED, UmlNavigability.SOURCE_ONLY, "{$DO $OH}-"),
                Triple(AggregationKind.COMPOSITE, UmlNavigability.SOURCE_ONLY, "{$DF $OH}-"),
            )
        for ((agg, nav, expected) in table) {
            "override for $agg / $nav" { UmlEdgeLatexRenderer.edgeEndsOverride(aggregation = agg, navigability = nav) shouldBe expected }
        }

        "standard association is a bare line, as in the SVG" {
            val override = UmlEdgeLatexRenderer.edgeEndsOverride(aggregation = AggregationKind.NONE, navigability = UmlNavigability.BOTH)
            draw(route = direct, style = UmlEdgeLatexRenderer.Style.ASSOCIATION, override = override) shouldBe
                "\\draw[kuml-association, -] $DIRECT_TAIL"
        }
        "direct route with composition" {
            draw(route = direct, style = UmlEdgeLatexRenderer.Style.ASSOCIATION, override = "{$DF}-") shouldBe
                "\\draw[kuml-association, {$DF}-] $DIRECT_TAIL"
        }
        "polyline keeps rounded corners after the override" {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 50f, y = 100f),
                    waypoints = listOf(Point(x = 0f, y = 50f)),
                    cornerRadiusPx = 4f,
                )
            draw(route = route, style = UmlEdgeLatexRenderer.Style.ASSOCIATION, override = "{$DO}-") shouldBe
                "\\draw[kuml-association, {$DO}-, rounded corners=4.000pt] " +
                "(0.000pt, -0.000pt) -- (0.000pt, -50.000pt) -- (50.000pt, -100.000pt);"
        }
        "bezier with override" {
            val route =
                EdgeRoute.Bezier(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 50f, y = 100f),
                    controlPoints = listOf(Point(x = 0f, y = 40f), Point(x = 50f, y = 60f)),
                )
            draw(route = route, style = UmlEdgeLatexRenderer.Style.ASSOCIATION, override = "-{$OH}") shouldBe
                "\\draw[kuml-association, -{$OH}] (0.000pt, -0.000pt) .. controls " +
                "(0.000pt, -40.000pt) and (50.000pt, -60.000pt) .. (50.000pt, -100.000pt);"
        }

        "class fixture draws every end kind" {
            val tex = UmlEdgeEndsFixtures.renderClass()
            tex shouldContain "\\draw[kuml-association, {$DF}-] (90.000pt, -80.000pt)"
            tex shouldContain "\\draw[kuml-association, {$DO}-{$OH}]"
            tex shouldContain "\\draw[kuml-association, {$DF $OH}-]"
            tex shouldContain "\\draw[kuml-association, -{$OH}]"
            tex shouldContain "\\draw[kuml-association, -] (90.000pt, -960.000pt)"
        }
        "use case fixture draws include, extend and connector" {
            val tex = UmlEdgeEndsFixtures.renderUseCase()
            tex shouldContain "\\draw[kuml-dependency, -{$OH}] (210.000pt, -80.000pt) -- (90.000pt, -200.000pt);"
            tex shouldContain "\\draw[kuml-dependency, -{$OH}] (250.000pt, -80.000pt)"
            tex shouldContain "\\draw[kuml-edge-plain, -] (290.000pt, -80.000pt) -- (410.000pt, -200.000pt);"
        }
        "preamble tikzset is unchanged" {
            val tex = UmlEdgeEndsFixtures.renderClass()
            tex shouldContain "kuml-association/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},"
            tex shouldContain "kuml-dependency/.style={draw=black, line width=0.5pt, dashed, -{Stealth[length=2mm]}},"
            tex shouldContain "kuml-edge-plain/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},"
        }

        // ── line end lengths stay consistent with the override strings ──────
        for (agg in AggregationKind.values()) {
            for (nav in UmlNavigability.values()) {
                "lineEnds matches edgeEndsOverride for $agg / $nav" {
                    val override = UmlEdgeLatexRenderer.edgeEndsOverride(aggregation = agg, navigability = nav)
                    val cut = override!!.indexOf('-')
                    val expectedSource = tipLength(override.substring(0, cut))
                    val expectedTarget = tipLength(override.substring(cut + 1))
                    val (source, target) = UmlEdgeLatexRenderer.lineEnds(aggregation = agg, navigability = nav)
                    source.lengthPt shouldBe expectedSource
                    target.lengthPt shouldBe expectedTarget
                }
            }
        }
        "tip constants are built from the length constants" {
            UmlEdgeLatexRenderer.DIAMOND_OPEN shouldBe "Diamond[open, length=16pt, width=10pt]"
            UmlEdgeLatexRenderer.DIAMOND_FILLED shouldBe "Diamond[length=16pt, width=10pt]"
            UmlEdgeLatexRenderer.OPEN_HEAD shouldBe "Straight Barb[length=12pt, width=10pt]"
            UmlEdgeLatexRenderer.DIAMOND_LENGTH_PT shouldBe 16
            UmlEdgeLatexRenderer.OPEN_HEAD_LENGTH_PT shouldBe 12
        }
    })
