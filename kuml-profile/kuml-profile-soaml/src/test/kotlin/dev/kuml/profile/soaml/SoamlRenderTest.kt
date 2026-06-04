package dev.kuml.profile.soaml

import dev.kuml.core.dsl.classDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.collaboration
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * AP-4.3 render tests for the SoaML order-processing example diagram.
 *
 * Builds the Order Processing SOA diagram programmatically and validates
 * that the SVG output contains all required SoaML stereotype labels.
 *
 * The diagram structure mirrors what soaml-order-processing.kuml.kts would produce:
 * - OrderService (Participant, Component) — V1.1.1: Component instead of Class
 * - PaymentService (Participant, Component) — V1.1.1: Component instead of Class
 * - OrderMessage (MessageType, Class)
 * - OrderPaymentContract (ServiceContract, Collaboration) with two roles
 */
class SoamlRenderTest :
    FunSpec({

        // ── Build the Order Processing SOA diagram ────────────────────────────────

        val diagram =
            classDiagram("Order Processing SOA") {
                applyProfile(soamlProfile)

                val orderService =
                    component("OrderService") {
                        stereotype("Participant")
                    }

                val paymentService =
                    component("PaymentService") {
                        stereotype("Participant")
                    }

                classOf("OrderMessage") {
                    stereotype("MessageType")
                    attribute(name = "orderId", type = "UUID")
                    attribute(name = "totalAmount", type = "BigDecimal")
                }

                collaboration("OrderPaymentContract") {
                    stereotype("ServiceContract")
                    role(name = "provider", type = orderService.name)
                    role(name = "consumer", type = paymentService.name)
                }
            }

        // Build layout result for all nodes in the diagram
        fun buildLayout(): LayoutResult {
            val nodeIds =
                diagram.elements
                    .filter { it is dev.kuml.uml.UmlNamedElement }
                    .map { NodeId(it.id) }

            val nodeEntries =
                nodeIds.mapIndexed { i, nodeId ->
                    val col = i % 3
                    val row = i / 3
                    nodeId to
                        NodeLayout(
                            bounds =
                                Rect(
                                    origin = Point(x = 20f + col * 200f, y = 20f + row * 120f),
                                    size = Size(width = 160f, height = 80f),
                                ),
                        )
                }
            val nodes = nodeEntries.toMap()

            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 660f, height = 300f),
                nodes = nodes,
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: SVG contains «Participant» at least 2 times ──────────────────

        test("SVG contains Participant stereotype label at least 2 times") {
            val layout = buildLayout()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // Count occurrences of «Participant»
            var count = 0
            var idx = svg.indexOf("«Participant»")
            while (idx >= 0) {
                count++
                idx = svg.indexOf("«Participant»", idx + 1)
            }
            count shouldBeGreaterThanOrEqual 2
        }

        // ── Test 2: SVG contains «ServiceContract» ────────────────────────────────

        test("SVG contains ServiceContract stereotype label") {
            val layout = buildLayout()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            svg shouldContain "«ServiceContract»"
        }

        // ── Test 3: SVG contains «MessageType» ───────────────────────────────────

        test("SVG contains MessageType stereotype label") {
            val layout = buildLayout()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            svg shouldContain "«MessageType»"
        }

        // ── Test 4: SVG contains all class/collaboration names ────────────────────

        test("SVG contains all expected element names") {
            val layout = buildLayout()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            svg shouldContain "OrderService"
            svg shouldContain "PaymentService"
            svg shouldContain "OrderMessage"
            svg shouldContain "OrderPaymentContract"
        }

        // ── Test 5: Rendering is deterministic (byte identity on two passes) ──────

        test("SoaML diagram renders deterministically") {
            val layout = buildLayout()
            val theme = PlainTheme()
            val svg1 = KumlSvgRenderer.toSvg(diagram, layout, theme)
            val svg2 = KumlSvgRenderer.toSvg(diagram, layout, theme)
            svg1 shouldBe svg2
        }

        // ── Test 6: Diagram has expected element count ─────────────────────────────

        test("Order Processing SOA diagram has 4 elements (2 components + 1 class + 1 collaboration)") {
            diagram.elements.size shouldBe 4
        }
    })
