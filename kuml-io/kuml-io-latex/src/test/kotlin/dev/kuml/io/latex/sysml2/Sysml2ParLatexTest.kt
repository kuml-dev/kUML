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
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("NewtonsLaw") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(220f, 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, par, layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "NewtonsLaw"
            // V2.0.12-Fallback emittiert das `«constraint»`-Stereotyp.
            tex shouldContain "constraint"

            SampleOutput.write("sysml2-par/newton-law-par.tex", tex)
        }

        "deterministic PAR output" {
            val model =
                sysml2Model("Det") {
                    val c =
                        constraintDef(
                            name = "C",
                            expression = "y = x",
                            parameters =
                                listOf(
                                    ConstraintParameter("x", "Real", ConstraintParameterDirection.In),
                                    ConstraintParameter("y", "Real", ConstraintParameterDirection.Out),
                                ),
                        )
                    parDiagram("P") {
                        include(c)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("C") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(200f, 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, par, layout)
            val two = KumlLatexRenderer.toLatex(model, par, layout)
            one shouldBe two
        }
    })
