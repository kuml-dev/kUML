package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-PAR-TikZ-Renderer
 * (V2.0.12, schließende Welle der SysML-2-Diagramm-Typ-Serie).
 *
 * V2.0.12 nutzt den BDD-Compartment-Pfad als Fallback für Constraints
 * (Rechteck mit `«constraint»`-Stereotyp). Das dreikompartimentige TikZ-
 * Pendant (Stereotyp + Name + Expression-Body + Parameter-Pin-Liste) ist
 * V2.x-Polish, analog zur BDD/IBD/UC/REQ/STM/ACT/SEQ-Geschichte.
 */
class Sysml2ParLatexTest :
    StringSpec({

        "PAR-TikZ enthält Constraint-Namen und «constraint»-Stereotyp (V2.0.12 fallback)" {
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
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("NewtonsLaw") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 220f, height = 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = par, layoutResult = layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "NewtonsLaw"
            // V2.0.12-Fallback emittiert das `«constraint»`-Stereotyp.
            tex shouldContain "constraint"

            SampleOutput.write(filename = "sysml2-par/newton-law-par.tex", content = tex)
        }

        "deterministic PAR output" {
            val model =
                sysml2Model(name = "Det") {
                    val c =
                        constraintDef(
                            name = "C",
                            expression = "y = x",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "x", typeId = "Real", direction = ConstraintParameterDirection.In),
                                    ConstraintParameter(name = "y", typeId = "Real", direction = ConstraintParameterDirection.Out),
                                ),
                        )
                    parDiagram(name = "P") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("C") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 200f, height = 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = par, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = par, layoutResult = layout)
            one shouldBe two
        }
    })
