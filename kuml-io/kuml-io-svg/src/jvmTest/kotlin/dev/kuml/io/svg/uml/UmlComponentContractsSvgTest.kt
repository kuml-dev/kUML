package dev.kuml.io.svg.uml

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.PackageDiagramConfig
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V2.0.47 — Lollipop-/Socket-Kurznotation für `provides`/`requires` in
 * UML-Komponentendiagrammen (siehe Vault-Beispiel
 * [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]]).
 */
class UmlComponentContractsSvgTest :
    FunSpec({

        fun componentLayout(
            comp: UmlComponent,
            interfaces: List<UmlInterface> = emptyList(),
        ): Pair<KumlDiagram, LayoutResult> {
            // dummy diagram type would be COMPONENT here; we construct the
            // layout directly so we don't depend on the engine.
            val diagram =
                KumlDiagram(
                    name = "T",
                    type = dev.kuml.core.model.DiagramType.COMPONENT,
                    elements = listOf(comp) + interfaces,
                )
            val nodes =
                mapOf(NodeId(comp.id) to NodeLayout(bounds = Rect(Point(50f, 100f), Size(200f, 80f)))) +
                    interfaces.mapIndexed { i, iface ->
                        NodeId(iface.id) to
                            NodeLayout(bounds = Rect(Point(260f + i * 220f, 100f), Size(200f, 80f)))
                    }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(800f, 360f),
                    nodes = nodes,
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            return diagram to layout
        }

        test("provides without interface node renders a lollipop above the component") {
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    providedInterfaceIds = listOf("IOrderApi"),
                )
            val (diagram, layout) = componentLayout(comp)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "contracts-OrderService"
            // Lollipop = full circle
            svg shouldContain "<circle"
            svg shouldContain "IOrderApi"
        }

        test("requires without interface node renders a socket (half-circle path) above the component") {
            val comp =
                UmlComponent(
                    id = "InvoiceService",
                    name = "InvoiceService",
                    requiredInterfaceIds = listOf("IOrderApi"),
                )
            val (diagram, layout) = componentLayout(comp)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "contracts-InvoiceService"
            // V2.0.48 — Socket muss UML-konform die Öffnung NACH AUSSEN
            // zeigen. In SVG-Pfadnotation heißt das `A r r 0 0 0` (sweep=0,
            // gegen den Uhrzeigersinn) — Bogen über die untere Kreishälfte,
            // Chord oben. Sweep=1 hätte den falschen, nach oben gewölbten
            // Bogen.
            svg shouldContain "A 8 8 0 0 0"
            svg shouldContain "IOrderApi"
        }

        test("when the interface IS a diagram node the lollipop is suppressed (explicit notation wins)") {
            val iface = UmlInterface(id = "IOrderApi", name = "IOrderApi")
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    providedInterfaceIds = listOf("IOrderApi"),
                )
            val (diagram, layout) = componentLayout(comp, interfaces = listOf(iface))
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // The contract group must NOT appear — explicit notation (= the
            // interface box plus a Realization edge synthesised at diagram
            // build time) is the canonical path.
            svg shouldNotContain "contracts-OrderService"
        }

        test("multiple unbound contracts are spread along the top edge") {
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    providedInterfaceIds = listOf("IOrderApi", "IShippingApi"),
                    requiredInterfaceIds = listOf("IEventBus"),
                )
            val (diagram, layout) = componentLayout(comp)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "IOrderApi"
            svg shouldContain "IShippingApi"
            svg shouldContain "IEventBus"
            // Three contracts → two lollipops (circles) + one socket (arc)
            val circleCount = Regex("<circle").findAll(svg).count()
            val arcCount = Regex(" A ").findAll(svg).count()
            circleCount shouldBe 2
            arcCount shouldBe 1
        }

        test("paddingPx is bumped so contracts above the component fit in the viewBox") {
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    providedInterfaceIds = listOf("IOrderApi"),
                )
            val (diagram, layout) = componentLayout(comp)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // viewBox height grows by (paddingBump - defaultPadding) on each
            // side; we don't assert the exact value to stay tolerant against
            // future tuning, but the lollipop's circle MUST sit at a positive
            // y — meaning it's not clipped at the top of the canvas.
            val circleMatch = Regex("""<circle\s+cx="[^"]+"\s+cy="([^"]+)"""").find(svg)
            requireNotNull(circleMatch) { "Expected at least one <circle> for the lollipop" }
            val cy = circleMatch.groupValues[1].toFloat()
            (cy >= 0f) shouldBe true
        }

        test("non-COMPONENT diagrams are unaffected by the contract pass") {
            // Same component model, but the diagram type is PACKAGE → no
            // lollipops should appear (rule: contracts only on UML component
            // diagrams).
            val comp =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    providedInterfaceIds = listOf("IOrderApi"),
                )
            val diagram =
                KumlDiagram(
                    name = "T",
                    type = dev.kuml.core.model.DiagramType.PACKAGE,
                    elements = listOf(comp),
                    config = PackageDiagramConfig(),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(400f, 200f),
                    nodes = mapOf(NodeId(comp.id) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(200f, 80f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            svg shouldNotContain "contracts-OrderService"
        }
    })
