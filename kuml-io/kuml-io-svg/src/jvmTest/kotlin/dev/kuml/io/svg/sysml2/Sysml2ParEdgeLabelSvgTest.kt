package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V2.x — verifiziert, dass der SVG-Renderer die `BindingConnectorUsage.name`
 * als Plain-Label auf jeder Binding-Edge ausgibt. Vor diesem Schritt rendert
 * der PAR-Renderer die drei Newton-Bindings (`F_to_force`, `m_to_mass`,
 * `a_to_acceleration`) als drei identische unbeschriftete Linien — der Leser
 * kann nicht mehr erkennen, welcher Constraint-Pin auf welches Vehicle-
 * Attribut bindet.
 */
class Sysml2ParEdgeLabelSvgTest :
    StringSpec({

        // Newton-Modell mit Vehicle-Part und drei expliziten Bindings.
        fun newtonModelWithVehicle(): Pair<Sysml2Model, ParDiagram> {
            val model =
                sysml2Model("NewtonModel") {
                    attributeDef("Mass")
                    attributeDef("Acceleration")
                    attributeDef("Force")

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

                    val vehicle =
                        partDef("Vehicle") {
                            attribute("mass", "Mass")
                            attribute("acceleration", "Acceleration")
                            attribute("force", "Force")
                        }

                    bind(name = "F_to_force", source = "NewtonsLaw::F", target = "Vehicle::force")
                    bind(name = "m_to_mass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind(name = "a_to_acceleration", source = "NewtonsLaw::a", target = "Vehicle::acceleration")

                    parDiagram("Newton — F = m·a applied to Vehicle") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            return model to par
        }

        // Layout mit Constraint oben, Vehicle unten und drei parallelen
        // direkten Binding-Edges in der Mitte. Midpoints horizontal ~80 px
        // auseinander (> 40 px CLUSTER_RADIUS), sodass jede Label-Zelle
        // eigenständig oberhalb der jeweiligen Linie sitzt.
        fun newtonLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(420f, 460f),
                nodes =
                    mapOf(
                        NodeId("NewtonsLaw") to NodeLayout(bounds = Rect(Point(60f, 30f), Size(300f, 150f))),
                        NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(60f, 290f), Size(300f, 140f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("binding:NewtonsLaw::F::Vehicle::force") to
                            EdgeRoute.Direct(source = Point(140f, 180f), target = Point(140f, 290f)),
                        EdgeId("binding:NewtonsLaw::m::Vehicle::mass") to
                            EdgeRoute.Direct(source = Point(220f, 180f), target = Point(220f, 290f)),
                        EdgeId("binding:NewtonsLaw::a::Vehicle::acceleration") to
                            EdgeRoute.Direct(source = Point(300f, 180f), target = Point(300f, 290f)),
                    ),
                groups = emptyMap(),
            )

        "all three binding names appear as edge labels" {
            val (model, par) = newtonModelWithVehicle()
            val svg = KumlSvgRenderer.toSvg(model, par, newtonLayout(), PlainTheme())

            svg shouldContain "F_to_force"
            svg shouldContain "m_to_mass"
            svg shouldContain "a_to_acceleration"

            SampleOutput.write("sysml2-edge-labels/par-newton-bindings.svg", svg)
        }

        "binding edges are solid (no stroke-dasharray on the binding lines)" {
            val (model, par) = newtonModelWithVehicle()
            val svg = KumlSvgRenderer.toSvg(model, par, newtonLayout(), PlainTheme())

            // The PAR diagram has no dashed edges at all. The string
            // `stroke-dasharray` legitimately appears in the SVG <defs>
            // CSS rule for `.kuml-edge-dashed` (a class that PAR never
            // applies); we therefore only assert that no edge element
            // carries the inline attribute form `stroke-dasharray="…"`,
            // which is how the renderer styles per-edge dashes.
            svg shouldNotContain "stroke-dasharray=\""
        }

        "binding edges carry no stereotype" {
            val (model, par) = newtonModelWithVehicle()
            val svg = KumlSvgRenderer.toSvg(model, par, newtonLayout(), PlainTheme())

            // SysML 2 PAR bindings carry no «binding» stereotype label —
            // only the plain name slot is populated.
            svg shouldNotContain "«binding»"
        }
    })
