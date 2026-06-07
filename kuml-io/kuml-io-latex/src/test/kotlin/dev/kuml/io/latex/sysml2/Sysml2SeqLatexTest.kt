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
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-SEQ-TikZ-Renderer
 * (V2.0.11).
 *
 * V2.0.11 nutzt den BDD-Compartment-Pfad als Fallback für Lifelines
 * (Rechteck mit `«lifeline»`-Stereotyp). Das axis-orientierte TikZ-Pendant
 * (Lifeline-Kopf + vertikale gestrichelte Zeit-Achse + horizontale
 * Nachrichten-Pfeile) erfordert separate TikZ-Pfad-Berechnung und ist
 * V2.x-Polish, idealerweise via `pgf-umlsd`. Symmetrie zum SVG-Pfad bleibt
 * dadurch eingeschränkt: SEQ-Nachrichten erscheinen NICHT im TikZ-Output.
 */
class Sysml2SeqLatexTest :
    StringSpec({

        "SEQ-TikZ enthält Lifeline-Namen und «lifeline»-Stereotyp (V2.0.11 fallback)" {
            val model =
                sysml2Model("LoginFlow") {
                    val user = lifelineDef("user")
                    val browser = lifelineDef("browser")
                    val auth = lifelineDef("authService")
                    message("login", user, browser, seqNo = 0)
                    message("validate", browser, auth, seqNo = 1)
                    seqDiagram("Login") {
                        include(user)
                        include(browser)
                        include(auth)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(600f, 240f),
                    nodes =
                        mapOf(
                            NodeId("user") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(140f, 160f))),
                            NodeId("browser") to NodeLayout(bounds = Rect(Point(180f, 20f), Size(140f, 160f))),
                            NodeId("authService") to NodeLayout(bounds = Rect(Point(340f, 20f), Size(140f, 160f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, seq, layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "user"
            tex shouldContain "browser"
            tex shouldContain "authService"
            // V2.0.11-Fallback emittiert das `«lifeline»`-Stereotyp.
            tex shouldContain "lifeline"

            SampleOutput.write("sysml2-seq/login-flow-seq.tex", tex)
        }

        "deterministic SEQ output" {
            val model =
                sysml2Model("Det") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("ping", a, b, seqNo = 0)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("a") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(140f, 150f))),
                            NodeId("b") to NodeLayout(bounds = Rect(Point(200f, 0f), Size(140f, 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, seq, layout)
            val two = KumlLatexRenderer.toLatex(model, seq, layout)
            one shouldBe two
        }
    })
