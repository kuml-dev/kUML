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
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-UC-TikZ-Renderer (V2.0.7).
 *
 * V2.0.7 nutzt den BDD-Compartment-Pfad als Fallback für Actors + UseCases
 * (Rechteck mit `«actor def»`/`«use case def»`-Header + Name). Stickfigur-
 * und Ellipsen-Stil in TikZ landen in V2.x — analog zur BDD/IBD-Geschichte
 * im LaTeX-Renderer.
 */
class Sysml2UcLatexTest :
    StringSpec({

        "UC-TikZ enthält Actor- und UseCase-Namen im Snippet (V2.0.7 fallback)" {
            val model =
                sysml2Model(name = "Library") {
                    val reader = actorDef(name = "Reader")
                    val borrow = useCaseDef(name = "BorrowBook")
                    val auth = useCaseDef(name = "Authenticate")
                    ucDiagram(name = "UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        association(actor = reader, useCase = borrow)
                        include(source = borrow, target = auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 600f, height = 220f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 60f), size = Size(width = 60f, height = 100f))),
                            NodeId("BorrowBook") to
                                NodeLayout(bounds = Rect(origin = Point(x = 160f, y = 30f), size = Size(width = 160f, height = 70f))),
                            NodeId("Authenticate") to
                                NodeLayout(bounds = Rect(origin = Point(x = 400f, y = 30f), size = Size(width = 160f, height = 70f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = uc, layoutResult = layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Reader"
            tex shouldContain "BorrowBook"
            tex shouldContain "Authenticate"
            // V2.0.7-Fallback emittiert die Stereotyp-Header der BDD-Boxen.
            tex shouldContain "actor def"
            tex shouldContain "use case def"

            SampleOutput.write(filename = "sysml2-uc/library-uc.tex", content = tex)
        }

        "deterministic UC output" {
            val model =
                sysml2Model(name = "Det") {
                    val reader = actorDef(name = "Reader")
                    val borrow = useCaseDef(name = "BorrowBook")
                    ucDiagram(name = "UC") {
                        include(reader)
                        include(borrow)
                        association(actor = reader, useCase = borrow)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 60f, height = 100f))),
                            NodeId("BorrowBook") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 0f), size = Size(width = 160f, height = 70f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = uc, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = uc, layoutResult = layout)
            one shouldBe two
        }
    })
