package dev.kuml.io.svg

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * V1.1.3 Ticket 2.5 — UmlStateMachine frame renderer.
 *
 * Pattern derived from UmlCollaborationSvg (stereotype header) and
 * UmlInteractionOverviewFrame.INTERACTION_REF (top-left label).
 */
class UmlStateMachineSvgTest :
    FunSpec({

        fun singleNodeLayout(id: String): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(280f, 200f),
                nodes =
                    mapOf(
                        NodeId(id) to
                            NodeLayout(bounds = Rect(Point(10f, 10f), Size(240f, 140f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun stereoApp(name: String) =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = name,
            )

        test("stateMachine without stereotype renders frame with label and name") {
            val sm = UmlStateMachine(id = "sm1", name = "OrderProcessing")
            val diagram = KumlDiagram(name = "D", elements = listOf(sm))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("sm1"), PlainTheme())

            svg shouldContain "class=\"kuml-frame\""
            svg shouldContain "stateMachine"
            svg shouldContain "OrderProcessing"
        }

        test("stateMachine with single stereotype renders «BehaviorSpec» header above name") {
            val sm =
                UmlStateMachine(
                    id = "sm2",
                    name = "OrderProcessing",
                    appliedStereotypes = listOf(stereoApp("BehaviorSpec")),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(sm))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("sm2"), PlainTheme())

            svg shouldContain "«BehaviorSpec»"
            svg shouldContain "OrderProcessing"

            // Stereotype header must appear before the name in document order
            val stereoPos = svg.indexOf("«BehaviorSpec»")
            val namePos = svg.indexOf("OrderProcessing")
            (stereoPos < namePos) shouldBe true
        }

        test("stateMachine with multiple stereotypes joined by joinSeparator") {
            val sm =
                UmlStateMachine(
                    id = "sm3",
                    name = "X",
                    appliedStereotypes =
                        listOf(stereoApp("A"), stereoApp("B")),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(sm))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("sm3"), PlainTheme())

            // Default joinSeparator is ", "
            svg shouldContain "«A, B»"
        }

        test("dispatcher routes UmlStateMachine to its frame renderer (not fallback)") {
            // The fallback renderer uses class="kuml-class" and prints the element ID.
            // The frame renderer uses class="kuml-frame" and prints the element name.
            val sm = UmlStateMachine(id = "sm4", name = "DispatchTarget")
            val diagram = KumlDiagram(name = "D", elements = listOf(sm))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("sm4"), PlainTheme())

            svg shouldContain "class=\"kuml-frame\""
            // The frame renderer prints the friendly name, not the bare ID.
            svg shouldContain "DispatchTarget"
        }

        test("stateMachine frame label is 'stateMachine' (top-left)") {
            val sm = UmlStateMachine(id = "sm5", name = "X")
            val diagram = KumlDiagram(name = "D", elements = listOf(sm))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("sm5"), PlainTheme())

            // The pretty-printer breaks the label across lines, so check the
            // attributes and the inner text independently.
            svg shouldContain "class=\"kuml-small\""
            svg shouldContain "x=\"8\""
            svg shouldContain "y=\"16\""
            svg shouldContain "stateMachine"
        }
    })
