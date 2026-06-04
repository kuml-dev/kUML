package dev.kuml.profile.spring

import dev.kuml.core.dsl.classDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.profile.javaee.javaEeProfile
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.returns
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * AP-5a.2 render tests for the Spring profile.
 *
 * Validates the Payment Service example diagram — both profiles applied simultaneously.
 * Tests the comma-joined multi-stereotype header and SpringData rendering.
 */
class SpringRenderTest :
    FunSpec({

        // Payment Service diagram — both profiles applied simultaneously
        val diagram =
            classDiagram("Payment Service") {
                applyProfile(javaEeProfile)
                applyProfile(springProfile)

                classOf("PaymentProcessor") {
                    stereotype("Service") // from JavaEE
                    // from Spring
                    stereotype("RestController") {
                        "produces" to "application/json"
                    }
                    operation(name = "process") { returns("PaymentResult") }
                }

                classOf("PaymentRepository") {
                    stereotype("SpringData") { "readOnly" to false }
                    operation(name = "findByOrderId") { returns("Payment") }
                }
            }

        fun buildLayout(): LayoutResult {
            val nodeIds =
                diagram.elements
                    .filterIsInstance<UmlNamedElement>()
                    .map { NodeId(it.id) }

            val nodeEntries =
                nodeIds.mapIndexed { i, nodeId ->
                    nodeId to
                        NodeLayout(
                            bounds =
                                Rect(
                                    origin = Point(x = 20f + i * 240f, y = 20f),
                                    size = Size(width = 200f, height = 100f),
                                ),
                        )
                }
            val nodes = nodeEntries.toMap()

            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 520f, height = 160f),
                nodes = nodes,
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: multi-stereotype comma-joined header ──────────────────────────

        test("SVG contains comma-joined 'Service, RestController' header for PaymentProcessor") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«Service, RestController»"
        }

        // ── Test 2: SpringData stereotype header ──────────────────────────────────

        test("SVG contains SpringData stereotype label for PaymentRepository") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«SpringData»"
        }

        // ── Test 3: class names appear in SVG ─────────────────────────────────────

        test("SVG contains both class names") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "PaymentProcessor"
            svg shouldContain "PaymentRepository"
        }
    })
