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
                sysml2Model("Library") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        association(reader, borrow)
                        include(borrow, auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(600f, 220f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to
                                NodeLayout(bounds = Rect(Point(20f, 60f), Size(60f, 100f))),
                            NodeId("BorrowBook") to
                                NodeLayout(bounds = Rect(Point(160f, 30f), Size(160f, 70f))),
                            NodeId("Authenticate") to
                                NodeLayout(bounds = Rect(Point(400f, 30f), Size(160f, 70f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, uc, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Reader"
            tex shouldContain "BorrowBook"
            tex shouldContain "Authenticate"
            // V2.0.7-Fallback emittiert die Stereotyp-Header der BDD-Boxen.
            tex shouldContain "actor def"
            tex shouldContain "use case def"

            SampleOutput.write("sysml2-uc/library-uc.tex", tex)
        }

        "deterministic UC output" {
            val model =
                sysml2Model("Det") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        association(reader, borrow)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to
                                NodeLayout(bounds = Rect(Point(0f, 0f), Size(60f, 100f))),
                            NodeId("BorrowBook") to
                                NodeLayout(bounds = Rect(Point(120f, 0f), Size(160f, 70f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, uc, layout)
            val two = KumlLatexRenderer.toLatex(model, uc, layout)
            one shouldBe two
        }
    })
