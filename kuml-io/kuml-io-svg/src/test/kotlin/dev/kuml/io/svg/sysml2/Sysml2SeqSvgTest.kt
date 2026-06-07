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
    })
