package dev.kuml.io.svg

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.uml.renderUmlState
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
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

        // ── Composite State rendering ─────────────────────────────────────────

        test("simple state renders name vertically centered") {
            val state = UmlState(id = "s1", name = "Draft")
            val layout = NodeLayout(bounds = Rect(Point(0f, 0f), Size(120f, 40f)))
            val builder = SvgBuilder(pretty = false)
            renderUmlState(state, layout, PlainTheme(), builder)
            val svg = builder.toString()

            svg shouldContain "Draft"
            // y = h/2 + 4 = 24
            svg shouldContain "y=\"24\""
            // no divider line for simple states
            (svg.contains("kuml-divider")) shouldBe false
        }

        test("composite state renders name at top with divider line") {
            val sub = UmlState(id = "sub", name = "Sub")
            val composite = UmlState(id = "comp", name = "Processing", substates = listOf(sub))
            val layout = NodeLayout(bounds = Rect(Point(0f, 0f), Size(160f, 100f)))
            val builder = SvgBuilder(pretty = false)
            renderUmlState(composite, layout, PlainTheme(), builder)
            val svg = builder.toString()

            svg shouldContain "Processing"
            // name baseline at y=18
            svg shouldContain "y=\"18\""
            // horizontal divider at y=28
            svg shouldContain "y1=\"28\""
            svg shouldContain "y2=\"28\""
            svg shouldContain "class=\"kuml-divider\""
        }

        test("renderUmlStateDiagram renders composite state frame from group layout") {
            val picking = UmlState(id = "picking", name = "Picking")
            val packing = UmlState(id = "packing", name = "Packing")
            val processing =
                UmlState(id = "processing", name = "Processing", substates = listOf(picking, packing))
            val start = UmlPseudostate(id = "start", name = "", kind = PseudostateKind.INITIAL)
            val sm =
                UmlStateMachine(
                    id = "sm6",
                    name = "OrderLifecycle",
                    vertices = listOf(start, processing),
                    transitions =
                        listOf(UmlTransition(id = "t1", sourceId = "start", targetId = "processing")),
                )
            val diagram = KumlDiagram(name = "D", type = DiagramType.STATE, elements = listOf(sm))

            // Provide a manually crafted LayoutResult that mimics what ELK would produce:
            // SM group, composite group nested inside, substate nodes, start node.
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(400f, 400f),
                    groups =
                        mapOf(
                            GroupId("sm6") to GroupLayout(bounds = Rect(Point(10f, 10f), Size(360f, 360f))),
                            GroupId("processing") to GroupLayout(bounds = Rect(Point(50f, 100f), Size(260f, 200f))),
                        ),
                    nodes =
                        mapOf(
                            NodeId("start") to NodeLayout(bounds = Rect(Point(180f, 30f), Size(24f, 24f))),
                            NodeId("picking") to NodeLayout(bounds = Rect(Point(60f, 140f), Size(100f, 40f))),
                            NodeId("packing") to NodeLayout(bounds = Rect(Point(180f, 140f), Size(100f, 40f))),
                        ),
                    edges = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            // State machine frame
            svg shouldContain "stateMachine"
            svg shouldContain "OrderLifecycle"
            // Composite state rendered as a group frame with name at top and divider
            svg shouldContain "Processing"
            svg shouldContain "class=\"kuml-divider\""
            // Substates rendered as simple state nodes
            svg shouldContain "Picking"
            svg shouldContain "Packing"
        }
    })
