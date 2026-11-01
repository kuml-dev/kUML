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
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
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
                sysml2Model(name = "NewtonModel") {
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "F", typeId = "Force", direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter(name = "m", typeId = "Mass", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram(name = "Newton") {
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
                canvas = Size(width = 300f, height = 200f),
                nodes =
                    mapOf(
                        NodeId(nodeId) to
                            NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 220f, height = 150f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        "PAR renders constraint with «constraint» stereotype and expression body" {
            val (model, par) = newtonModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = fakeLayout("NewtonsLaw"), theme = PlainTheme())

            svg shouldContain "id=\"NewtonsLaw\""
            svg shouldContain "«constraint»"
            svg shouldContain "NewtonsLaw"
            svg shouldContain "F = m * a"
            // The expression body is rendered in monospace.
            svg shouldContain "font-family=\"monospace\""

            SampleOutput.write(filename = "sysml2-par/constraint-with-expression.svg", content = svg)
        }

        "PAR constraint with no parameters omits the parameter compartment" {
            val model =
                sysml2Model(name = "BareConstraint") {
                    val c =
                        constraintDef(
                            name = "Idle",
                            expression = "x = x",
                            parameters = emptyList(),
                        )
                    parDiagram(name = "Idle") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = fakeLayout("Idle"), theme = PlainTheme())

            svg shouldContain "Idle"
            svg shouldContain "x = x"
            // No direction stereotypes since the parameter list is empty.
            svg shouldNotContain "«in»"
            svg shouldNotContain "«out»"
            svg shouldNotContain "«inout»"

            SampleOutput.write(filename = "sysml2-par/constraint-without-parameters.svg", content = svg)
        }

        "PAR constraint with parameters shows direction stereotypes («in» / «out» / «inout»)" {
            val model =
                sysml2Model(name = "MixedDirections") {
                    val c =
                        constraintDef(
                            name = "Mixed",
                            expression = "y = f(x)",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "x", typeId = "Real", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "y", typeId = "Real", direction = ConstraintParameterDirection.Out),
                                    ConstraintParameter(name = "k", typeId = "Real", direction = ConstraintParameterDirection.Inout),
                                ),
                        )
                    parDiagram(name = "Mixed") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = fakeLayout("Mixed"), theme = PlainTheme())

            svg shouldContain "«in» x : Real"
            svg shouldContain "«out» y : Real"
            svg shouldContain "«inout» k : Real"

            SampleOutput.write(filename = "sysml2-par/parameter-directions.svg", content = svg)
        }

        "PAR expression of 60 characters is complete and a 120 character one wraps into two text lines" {
            val expr60 = "alpha_beta_gamma_delta_epsilon_zeta_eta_theta_iota_kappa_lam"
            expr60.length shouldBe 60
            val expr120 = "$expr60 && omega_psi_chi_phi_upsilon_tau_sigma_rho_pi_omicron_x"
            val model =
                sysml2Model(name = "LongExpr") {
                    val short = constraintDef(name = "Short", expression = expr60, parameters = emptyList())
                    val long = constraintDef(name = "Long", expression = expr120, parameters = emptyList())
                    parDiagram(name = "Long") {
                        include(short)
                        include(long)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 800f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Short") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 440f, height = 80f))),
                            NodeId("Long") to
                                NodeLayout(bounds = Rect(origin = Point(x = 480f, y = 20f), size = Size(width = 440f, height = 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = layout, theme = PlainTheme())

            Regex(""">\s*$expr60\s*<""").containsMatchIn(svg) shouldBe true
            svg shouldNotContain "…"
            Regex(""">\s*&amp;&amp; omega_psi_chi_phi_upsilon_tau_sigma_rho_pi_omicron_x\s*<""").containsMatchIn(svg) shouldBe true
            Regex("""font-family="monospace"""").findAll(svg).count() shouldBe 3

            SampleOutput.write(filename = "sysml2-par/long-expression-wrapped.svg", content = svg)
        }

        "PAR parameter shows the unit of its AttributeDefinition" {
            val model =
                sysml2Model(name = "Units") {
                    attributeDef(name = "Druck", unitSymbol = "Pa")
                    val c =
                        constraintDef(
                            name = "Mix",
                            expression = "pMix = p1 + p2",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "pMix", typeId = "Druck", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram(name = "Units") { include(c) }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = fakeLayout("Mix"), theme = PlainTheme())

            svg shouldContain "«in» pMix : Druck [Pa]"

            SampleOutput.write(filename = "sysml2-par/parameter-unit.svg", content = svg)
        }

        "PAR pin squares in the SVG match the bridge pin points" {
            val (model, par) = newtonModel()
            val layout = fakeLayout("NewtonsLaw")
            val c = model.definitions.filterIsInstance<dev.kuml.sysml2.ConstraintDefinition>().single()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = layout, theme = PlainTheme())
            val bounds = layout.nodes.getValue(NodeId("NewtonsLaw")).bounds
            val pinRects =
                Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="8" height="8" class="kuml-class kuml-par-pin"""").findAll(svg).toList()
            pinRects.size shouldBe c.parameters.size
            c.parameters.indices.forEach { i ->
                val p =
                    dev.kuml.layout.bridge.ParConstraintGeometry
                        .pinPoint(constraint = c, index = i, bounds = bounds)
                // the group is translated by the node origin plus the canvas padding; compare in local box coordinates
                val local = pinRects[i].groupValues
                val cx = local[1].toFloat() + 4f
                val cy = local[2].toFloat() + 4f
                kotlin.math.abs(cy - (p.y - bounds.origin.y)) shouldBeLessThanOrEqual 0.01f
                kotlin.math.abs(cx - (p.x - bounds.origin.x)) shouldBeLessThanOrEqual 0.01f
            }
        }

        "deterministic output — same input renders byte-identically" {
            val (model, par) = newtonModel()
            val theme = PlainTheme()
            val layout = fakeLayout("NewtonsLaw")

            val svgA = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = layout, theme = theme)
            val svgB = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = layout, theme = theme)
            svgA shouldBe svgB
        }
    })
