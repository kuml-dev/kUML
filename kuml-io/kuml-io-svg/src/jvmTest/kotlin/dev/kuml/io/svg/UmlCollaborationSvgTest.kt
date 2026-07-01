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
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlCollaboration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SVG rendering tests for [UmlCollaboration] (AP-3.5.3).
 */
class UmlCollaborationSvgTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun singleNodeLayout(
            id: String,
            x: Float = 10f,
            y: Float = 10f,
            w: Float = 160f,
            h: Float = 80f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 20f, h + 20f),
                nodes =
                    mapOf(
                        NodeId(id) to NodeLayout(bounds = Rect(Point(x, y), Size(w, h))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        // ── Test 1: empty collaboration renders as dashed ellipse with name ───────

        test("collaboration renders as dashed ellipse with name centred") {
            val collab = UmlCollaboration(id = "c1", name = "OrderPlacement")
            val diagram = KumlDiagram(name = "D", elements = listOf(collab))
            val layout = singleNodeLayout("c1")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // The ellipse element must be present
            svg shouldContain "<ellipse"
            // The dashed stroke pattern
            svg shouldContain "stroke-dasharray"
            // The name
            svg shouldContain "OrderPlacement"
            // The CSS class for collaboration
            svg shouldContain "kuml-collaboration"
        }

        // ── Test 2: collaboration with stereotype renders «…» header ──────────────

        test("collaboration with stereotype renders «stereotype» header above name") {
            val app =
                KumlStereotypeApplication(
                    profileNamespace = "dev.soaml",
                    stereotypeName = "ServiceContract",
                )
            val collab =
                UmlCollaboration(
                    id = "c2",
                    name = "TradeExecution",
                    appliedStereotypes = listOf(app),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(collab))
            val layout = singleNodeLayout("c2")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«ServiceContract»"
            svg shouldContain "TradeExecution"
            // Stereotype label must appear before the name in the SVG document order
            val stereoPos = svg.indexOf("«ServiceContract»")
            val namePos = svg.indexOf("TradeExecution")
            (stereoPos < namePos) shouldBe true
        }

        // ── Test 3: stroke-dasharray is present with correct pattern ─────────────

        test("collaboration SVG contains stroke-dasharray with value '4 4'") {
            val collab = UmlCollaboration(id = "c3", name = "PaymentProtocol")
            val diagram = KumlDiagram(name = "D", elements = listOf(collab))
            val layout = singleNodeLayout("c3")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "stroke-dasharray=\"4 4\""
        }

        // ── Test 4: diagrams without collaboration are byte-identical ─────────────

        test("diagram without collaboration produces byte-identical output across two renders") {
            val cls = UmlClass(id = "cls1", name = "Order")
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls1")
            val theme = PlainTheme()

            val svg1 = KumlSvgRenderer.toSvg(diagram, layout, theme)
            val svg2 = KumlSvgRenderer.toSvg(diagram, layout, theme)

            // Determinism check — adding UmlCollaboration did not break existing rendering
            svg1 shouldBe svg2
            // No collaboration-specific markup in a class-only diagram. The class
            // `.kuml-collaboration` is allowed to appear in the stylesheet defs
            // (V2.0.44 — added globally so Batik does not default to fill=black on
            // ellipses); only the ELEMENT-level usage must not appear.
            svg1 shouldNotContain "class=\"kuml-collaboration\""
            // The attribute-level dash pattern (as an SVG element attribute) must not appear
            svg1 shouldNotContain "stroke-dasharray=\"4 4\""
        }

        // ── Test 5: dispatcher routes UmlCollaboration correctly ──────────────────

        test("NodeRendererDispatcher dispatches UmlCollaboration to collaboration renderer") {
            NodeRendererDispatcher.dispatchKey(
                UmlCollaboration(id = "c5", name = "Test"),
            ) shouldBe "UmlCollaboration"
        }
    })
