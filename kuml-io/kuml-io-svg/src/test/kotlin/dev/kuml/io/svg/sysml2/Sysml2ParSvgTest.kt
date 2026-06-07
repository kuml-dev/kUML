package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-PAR-SVG-Renderer
 * (V2.0.12, schließende Welle der SysML-2-Diagramm-Typ-Serie).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-par/<name>.svg`, sodass es im
 * Browser visuell überprüft werden kann.
 */
class Sysml2ParSvgTest :
    StringSpec({

        // Klassisches Newton-Beispiel: F = m * a, mit `F` als Out- und `m`/`a`
        // als In-Parametern.
        fun newtonModel(): Pair<Sysml2Model, ParDiagram> {
            val model =
                sysml2Model("NewtonModel") {
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                                    ConstraintParameter("a", "Acceleration", ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram("Newton") {
                        include(newton)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            return model to par
        }

        fun fakeLayout(nodeId: String): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(300f, 200f),
                nodes =
                    mapOf(
                        NodeId(nodeId) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(220f, 150f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        "PAR renders constraint with «constraint» stereotype and expression body" {
            val (model, par) = newtonModel()
            val svg = KumlSvgRenderer.toSvg(model, par, fakeLayout("NewtonsLaw"), PlainTheme())

            svg shouldContain "id=\"NewtonsLaw\""
            svg shouldContain "«constraint»"
            svg shouldContain "NewtonsLaw"
            svg shouldContain "F = m * a"
            // The expression body is rendered in monospace.
            svg shouldContain "font-family=\"monospace\""

            SampleOutput.write("sysml2-par/constraint-with-expression.svg", svg)
        }

        "PAR constraint with no parameters omits the parameter compartment" {
            val model =
                sysml2Model("BareConstraint") {
                    val c =
                        constraintDef(
                            name = "Idle",
                            expression = "x = x",
                            parameters = emptyList(),
                        )
                    parDiagram("Idle") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model, par, fakeLayout("Idle"), PlainTheme())

            svg shouldContain "Idle"
            svg shouldContain "x = x"
            // No direction stereotypes since the parameter list is empty.
            svg shouldNotContain "«in»"
            svg shouldNotContain "«out»"
            svg shouldNotContain "«inout»"

            SampleOutput.write("sysml2-par/constraint-without-parameters.svg", svg)
        }

        "PAR constraint with parameters shows direction stereotypes («in» / «out» / «inout»)" {
            val model =
                sysml2Model("MixedDirections") {
                    val c =
                        constraintDef(
                            name = "Mixed",
                            expression = "y = f(x)",
                            parameters =
                                listOf(
                                    ConstraintParameter("x", "Real", ConstraintParameterDirection.In),
                                    ConstraintParameter("y", "Real", ConstraintParameterDirection.Out),
                                    ConstraintParameter("k", "Real", ConstraintParameterDirection.Inout),
                                ),
                        )
                    parDiagram("Mixed") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model, par, fakeLayout("Mixed"), PlainTheme())

            svg shouldContain "«in» x : Real"
            svg shouldContain "«out» y : Real"
            svg shouldContain "«inout» k : Real"

            SampleOutput.write("sysml2-par/parameter-directions.svg", svg)
        }

        "PAR long expression is truncated with ellipsis" {
            val longExpr = "F = m * a + b * c + d * e + f * g + h * i + j * k"
            val model =
                sysml2Model("LongExpr") {
                    val c =
                        constraintDef(
                            name = "Long",
                            expression = longExpr,
                            parameters = emptyList(),
                        )
                    parDiagram("Long") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model, par, fakeLayout("Long"), PlainTheme())

            // The expression is longer than the truncation threshold so the SVG
            // must contain the ellipsis suffix but not the full text.
            svg shouldContain "…"
            svg shouldNotContain longExpr

            SampleOutput.write("sysml2-par/long-expression-truncated.svg", svg)
        }

        "deterministic output — same input renders byte-identically" {
            val (model, par) = newtonModel()
            val theme = PlainTheme()
            val layout = fakeLayout("NewtonsLaw")

            val svgA = KumlSvgRenderer.toSvg(model, par, layout, theme)
            val svgB = KumlSvgRenderer.toSvg(model, par, layout, theme)
            svgA shouldBe svgB
        }
    })
