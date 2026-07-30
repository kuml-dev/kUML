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
                sysml2Model(name = "NewtonModel") {
                    attributeDef(name = "Mass")
                    attributeDef(name = "Acceleration")
                    attributeDef(name = "Force")

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

                    val vehicle =
                        partDef(name = "Vehicle") {
                            attribute(name = "mass", typeId = "Mass")
                            attribute(name = "acceleration", typeId = "Acceleration")
                            attribute(name = "force", typeId = "Force")
                        }

                    bind(name = "F_to_force", source = "NewtonsLaw::F", target = "Vehicle::force")
                    bind(name = "m_to_mass", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    bind(name = "a_to_acceleration", source = "NewtonsLaw::a", target = "Vehicle::acceleration")

                    parDiagram(name = "Newton — F = m·a applied to Vehicle") {
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
                canvas = Size(width = 420f, height = 460f),
                nodes =
                    mapOf(
                        NodeId("NewtonsLaw") to
                            NodeLayout(bounds = Rect(origin = Point(x = 60f, y = 30f), size = Size(width = 300f, height = 150f))),
                        NodeId("Vehicle") to
                            NodeLayout(bounds = Rect(origin = Point(x = 60f, y = 290f), size = Size(width = 300f, height = 140f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("binding:NewtonsLaw::F::Vehicle::force") to
                            EdgeRoute.Direct(source = Point(x = 140f, y = 180f), target = Point(x = 140f, y = 290f)),
                        EdgeId("binding:NewtonsLaw::m::Vehicle::mass") to
                            EdgeRoute.Direct(source = Point(x = 220f, y = 180f), target = Point(x = 220f, y = 290f)),
                        EdgeId("binding:NewtonsLaw::a::Vehicle::acceleration") to
                            EdgeRoute.Direct(source = Point(x = 300f, y = 180f), target = Point(x = 300f, y = 290f)),
                    ),
                groups = emptyMap(),
            )

        "all three binding names appear as edge labels" {
            val (model, par) = newtonModelWithVehicle()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = newtonLayout(), theme = PlainTheme())

            svg shouldContain "F_to_force"
            svg shouldContain "m_to_mass"
            svg shouldContain "a_to_acceleration"

            SampleOutput.write(filename = "sysml2-edge-labels/par-newton-bindings.svg", content = svg)
        }

        "binding edges are solid (no stroke-dasharray on the binding lines)" {
            val (model, par) = newtonModelWithVehicle()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = newtonLayout(), theme = PlainTheme())

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
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = par, layoutResult = newtonLayout(), theme = PlainTheme())

            // SysML 2 PAR bindings carry no «binding» stereotype label —
            // only the plain name slot is populated.
            svg shouldNotContain "«binding»"
        }
    })
