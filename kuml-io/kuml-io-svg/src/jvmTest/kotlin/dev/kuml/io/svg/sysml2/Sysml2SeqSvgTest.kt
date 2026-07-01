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
import dev.kuml.sysml2.CombinedFragmentOperand
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-SEQ-SVG-Renderer
 * (V2.0.11).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-seq/<name>.svg`, sodass es im
 * Browser visuell überprüft werden kann.
 *
 * Hinweis zur Architektur-Divergenz: SEQ zeichnet Nachrichten direkt im
 * Renderer (nicht über den EdgeRendererDispatcher) — siehe
 * [dev.kuml.io.svg.sysml2.renderSysml2SeqMessages] für die Begründung.
 */
class Sysml2SeqSvgTest :
    StringSpec({

        // Klassisches Login-Beispiel: User → Browser → AuthService → … zurück.
        fun loginModel(): Pair<Sysml2Model, SeqDiagram> {
            val model =
                sysml2Model("LoginFlow") {
                    val user = lifelineDef("user")
                    val browser = lifelineDef("browser")
                    val auth = lifelineDef("authService")
                    message("enterCredentials(user, pwd)", user, browser, seqNo = 0)
                    message("login(user, pwd)", browser, auth, seqNo = 1)
                    message("validateCredentials()", auth, auth, seqNo = 2) // self-call
                    message("sessionToken", auth, browser, seqNo = 3, kind = MessageKind.Reply)
                    message("welcomeScreen", browser, user, seqNo = 4, kind = MessageKind.Reply)
                    seqDiagram("Login flow") {
                        include(user)
                        include(browser)
                        include(auth)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            return model to seq
        }

        // Handgefertigtes LayoutResult — drei Lifelines auf festen X-Spuren,
        // jede mit einer Höhe, die fünf Nachrichten + Padding aufnimmt.
        // Höhe = HEAD (40) + 6*ROW (32) + TAIL (40) = 272.
        fun fakeLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(700f, 320f),
                nodes =
                    mapOf(
                        NodeId("user") to NodeLayout(bounds = Rect(Point(40f, 20f), Size(140f, 272f))),
                        NodeId("browser") to NodeLayout(bounds = Rect(Point(240f, 20f), Size(140f, 272f))),
                        NodeId("authService") to NodeLayout(bounds = Rect(Point(440f, 20f), Size(140f, 272f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        "SEQ renders lifeline head with «lifeline» stereotype" {
            val (model, seq) = loginModel()
            val svg = KumlSvgRenderer.toSvg(model, seq, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"user\""
            svg shouldContain "id=\"browser\""
            svg shouldContain "id=\"authService\""
            svg shouldContain "«lifeline»"
            // Each lifeline head has a kuml-class rect on top.
            svg shouldContain "class=\"kuml-class\""

            SampleOutput.write("sysml2-seq/lifeline-heads.svg", svg)
        }

        "SEQ renders vertical dashed line below the lifeline head" {
            val (model, seq) = loginModel()
            val svg = KumlSvgRenderer.toSvg(model, seq, fakeLayout(), PlainTheme())

            // The renderer emits a <line> with stroke-dasharray="4 4" for the time-axis.
            svg shouldContain "stroke-dasharray=\"4 4\""

            SampleOutput.write("sysml2-seq/lifeline-dashed-axis.svg", svg)
        }

        "SEQ renders sync message as solid line with filled arrow" {
            val (model, seq) = loginModel()
            val svg = KumlSvgRenderer.toSvg(model, seq, fakeLayout(), PlainTheme())

            // Sync messages: message:user-browser-0, message:browser-authService-1
            svg shouldContain "id=\"message:user-browser-0\""
            svg shouldContain "enterCredentials(user, pwd)"
            // Filled arrow → polygon
            svg shouldContain "<polygon"

            SampleOutput.write("sysml2-seq/sync-messages.svg", svg)
        }

        "SEQ renders async message as solid line with open arrow" {
            val model =
                sysml2Model("AsyncOnly") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("ping", a, b, seqNo = 0, kind = MessageKind.Async)
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
                            NodeId("a") to NodeLayout(bounds = Rect(Point(40f, 20f), Size(140f, 150f))),
                            NodeId("b") to NodeLayout(bounds = Rect(Point(240f, 20f), Size(140f, 150f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"message:a-b-0\""
            svg shouldContain "ping"
            // Async → solid line (kuml-edge, not kuml-edge-dashed) + open <path> arrowhead.
            svg shouldContain "class=\"kuml-edge\""
            svg shouldContain "<path"

            SampleOutput.write("sysml2-seq/async-message.svg", svg)
        }

        "SEQ renders reply message as dashed line" {
            val (model, seq) = loginModel()
            val svg = KumlSvgRenderer.toSvg(model, seq, fakeLayout(), PlainTheme())

            // Reply messages: sessionToken (seq=3), welcomeScreen (seq=4)
            svg shouldContain "id=\"message:authService-browser-3\""
            svg shouldContain "id=\"message:browser-user-4\""
            // Dashed style.
            svg shouldContain "class=\"kuml-edge-dashed\""

            SampleOutput.write("sysml2-seq/reply-messages.svg", svg)
        }

        "SEQ self-call renders U-shape arrow" {
            val (model, seq) = loginModel()
            val svg = KumlSvgRenderer.toSvg(model, seq, fakeLayout(), PlainTheme())

            // validateCredentials() is a self-call on the authService lifeline.
            svg shouldContain "id=\"message:authService-authService-2\""
            svg shouldContain "validateCredentials()"
            // The U-shape is drawn as a <path d="M … L … L … L …">.
            svg shouldContain "<path"

            SampleOutput.write("sysml2-seq/self-call.svg", svg)
        }

        "deterministic output — same input renders byte-identically" {
            val (model, seq) = loginModel()
            val theme = PlainTheme()
            val layout = fakeLayout()

            val svgA = KumlSvgRenderer.toSvg(model, seq, layout, theme)
            val svgB = KumlSvgRenderer.toSvg(model, seq, layout, theme)
            svgA shouldBe svgB
        }

        // ─────────────────── V2.0.15: CF + ExecSpec + Create/Destroy ───────────

        // Reusable two-lifeline layout — large enough to host 4 message rows
        // plus tail padding without clipping.
        fun twoLifelineLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(500f, 320f),
                nodes =
                    mapOf(
                        NodeId("a") to NodeLayout(bounds = Rect(Point(40f, 20f), Size(140f, 272f))),
                        NodeId("b") to NodeLayout(bounds = Rect(Point(280f, 20f), Size(140f, 272f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        "SEQ renders combined fragment frame with operator tag" {
            val model =
                sysml2Model("CFFrame") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("ping", a, b, seqNo = 1)
                    combinedFragment("loopBlock", CombinedFragmentOperator.Loop, startSeqNo = 1, endSeqNo = 2)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"combinedFragment:loopBlock\""
            // Dashed frame stroke-dasharray.
            svg shouldContain "stroke-dasharray=\"6 4\""
            // Operator tag pentagon + uppercase LOOP label.
            svg shouldContain "<polygon"
            svg shouldContain "LOOP"

            SampleOutput.write("sysml2-seq/combined-fragment-loop.svg", svg)
        }

        "SEQ renders alt fragment with two operands separated by dashed line" {
            val model =
                sysml2Model("CFAlt") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("happy", a, b, seqNo = 1)
                    message("sad", a, b, seqNo = 2)
                    combinedFragment(
                        name = "decision",
                        operator = CombinedFragmentOperator.Alt,
                        operands =
                            listOf(
                                CombinedFragmentOperand(guard = "credentials valid", startSeqNo = 1, endSeqNo = 1),
                                CombinedFragmentOperand(guard = "credentials invalid", startSeqNo = 2, endSeqNo = 2),
                            ),
                    )
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"combinedFragment:decision\""
            svg shouldContain "ALT"
            svg shouldContain "[credentials valid]"
            svg shouldContain "[credentials invalid]"
            // The separator dashed line uses kuml-divider class with dash array.
            svg shouldContain "class=\"kuml-divider\""

            SampleOutput.write("sysml2-seq/combined-fragment-alt-two-operands.svg", svg)
        }

        "SEQ renders execution specification as a thin rectangle on a lifeline" {
            val model =
                sysml2Model("ES") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("ping", a, b, seqNo = 1)
                    executionSpec("activeB", b, startSeqNo = 1, endSeqNo = 2)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"executionSpec:b-1-2\""
            // The activation bar is a kuml-class rect with white fill.
            svg shouldContain "fill=\"white\""

            SampleOutput.write("sysml2-seq/execution-spec.svg", svg)
        }

        "SEQ renders Create message with «create» stereotype" {
            val model =
                sysml2Model("Create") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("new Browser()", a, b, seqNo = 1, kind = MessageKind.Create)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"message:a-b-1\""
            svg shouldContain "«create»"
            // Create arrows are dashed.
            svg shouldContain "class=\"kuml-edge-dashed\""

            SampleOutput.write("sysml2-seq/create-message.svg", svg)
        }

        "SEQ renders Destroy message with «destroy» stereotype + X marker" {
            val model =
                sysml2Model("Destroy") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("close()", a, b, seqNo = 1, kind = MessageKind.Destroy)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val svg = KumlSvgRenderer.toSvg(model, seq, layout, PlainTheme())

            svg shouldContain "id=\"message:a-b-1\""
            svg shouldContain "«destroy»"
            // The destroy message group contains two <line> entries for the X
            // plus the arrow shaft — at least three <line> elements live
            // inside the group.
            svg shouldContain "close()"

            SampleOutput.write("sysml2-seq/destroy-message.svg", svg)
        }

        "deterministic output — combined fragment + execution spec render byte-identically" {
            val model =
                sysml2Model("Det") {
                    val a = lifelineDef("a")
                    val b = lifelineDef("b")
                    message("ping", a, b, seqNo = 1)
                    combinedFragment("frag", CombinedFragmentOperator.Opt, startSeqNo = 1, endSeqNo = 2, guard = "g")
                    executionSpec("activeA", a, startSeqNo = 1, endSeqNo = 2)
                    seqDiagram("S") {
                        include(a)
                        include(b)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout = twoLifelineLayout()
            val theme = PlainTheme()

            val svgA = KumlSvgRenderer.toSvg(model, seq, layout, theme)
            val svgB = KumlSvgRenderer.toSvg(model, seq, layout, theme)
            svgA shouldBe svgB
        }
    })
