package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SysML 2 BDD relations in LaTeX keep the meaning the SVG shows: filled diamond at the owner of a composition,
 * open V head for a reference part, no head for an association, and the multiplicity / role name next to the target
 * end. Structure checks only, no byte goldens.
 */
class Sysml2AssociationLatexTest :
    StringSpec({
        fun box(
            x: Float,
            y: Float,
        ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = 100f, height = 50f)))

        val model =
            sysml2Model(name = "Car") {
                val engine = partDef(name = "Engine")
                val wheel = partDef(name = "Wheel")
                val tank = partDef(name = "Tank")
                val driver = partDef(name = "Driver")
                val car =
                    partDef(name = "Car", id = "CarB") {
                        part(name = "engine", typeId = engine.id)
                        part(name = "wheels", typeId = wheel.id, multiplicity = KermlMultiplicity(lower = 4, upper = 4))
                        refPart(name = "tank", typeId = tank.id, multiplicity = KermlMultiplicity(lower = 0, upper = 1))
                    }
                connectionDef(name = "drives") {
                    part(name = "pilot", typeId = driver.id)
                    part(name = "ride", typeId = car.id)
                }
                bdd(name = "BDD") {
                    include(definition = car)
                    include(definition = engine)
                    include(definition = wheel)
                    include(definition = tank)
                    include(definition = driver)
                }
            }
        val diagram = model.diagrams.filterIsInstance<BdDiagram>().single()
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 600f, height = 400f),
                nodes =
                    mapOf(
                        NodeId("CarB") to box(200f, 120f),
                        NodeId("Engine") to box(20f, 300f),
                        NodeId("Wheel") to box(160f, 300f),
                        NodeId("Tank") to box(300f, 300f),
                        NodeId("Driver") to box(200f, 10f),
                    ),
                edges =
                    mapOf(
                        EdgeId("comp:CarB::engine") to
                            EdgeRoute.Direct(source = Point(x = 220f, y = 170f), target = Point(x = 70f, y = 300f)),
                        EdgeId("comp:CarB::wheels") to
                            EdgeRoute.Direct(source = Point(x = 240f, y = 170f), target = Point(x = 210f, y = 300f)),
                        EdgeId(
                            "agg:CarB::tank",
                        ) to EdgeRoute.Direct(source = Point(x = 280f, y = 170f), target = Point(x = 350f, y = 300f)),
                        EdgeId("link:drives") to EdgeRoute.Direct(source = Point(x = 250f, y = 60f), target = Point(x = 250f, y = 120f)),
                    ),
                groups = emptyMap(),
            )
        val tex = KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout)

        fun drawLine(prefix: String): String = tex.lines().first { it.contains(prefix) }

        "composition draws the filled diamond at the owner and the multiplicity at the target end" {
            val line = drawLine("(220.000pt, -170.000pt) --")
            line shouldContain "kuml-sysml2-edge-diamond-filled"
            val wheels = drawLine("(240.000pt, -170.000pt) --")
            wheels shouldContain "kuml-sysml2-edge-diamond-filled"
            tex shouldContain "{4};"
        }

        "reference part draws an open V head, never a filled one, and its multiplicity" {
            val line = drawLine("(280.000pt, -170.000pt) --")
            line shouldContain "Straight Barb"
            line shouldNotContain "kuml-sysml2-edge-solid"
            tex shouldContain "{0..1};"
        }

        "association has no head and carries the role name at the target end" {
            val line = drawLine("(250.000pt, -60.000pt) --")
            line shouldContain "kuml-sysml2-edge-binding"
            tex shouldContain "{ride};"
        }

        "output is deterministic" {
            KumlLatexRenderer.toLatex(model = model, diagram = diagram, layoutResult = layout) shouldBe tex
        }
    })
