package dev.kuml.profile.autosar

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
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.component
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.port
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * AP-5b.2 render tests for the AUTOSAR profile.
 *
 * Builds the Engine Control AUTOSAR diagram programmatically and validates
 * SVG output contains correct stereotype headers.
 *
 * Note: UmlPort is not rendered as a separate SVG node — ports are part of
 * the UmlComponent rendering. The «AutosarPort» stereotype is applied and
 * stored on the port model element, but does not appear as a standalone
 * SVG label. Tests verify component and interface stereotype headers instead.
 */
class AutosarRenderTest :
    FunSpec({

        // Engine Control AUTOSAR diagram
        val diagram =
            classDiagram("Engine Control AUTOSAR") {
                applyProfile(autosarProfile)

                component("EngineController") {
                    stereotype("SoftwareComponent") {
                        "kind" to AutosarSwcKind.Application
                        "packageName" to "powertrain"
                    }
                    port("rpmSensor") {
                        stereotype("AutosarPort") { "direction" to AutosarPortDirection.Required }
                    }
                    port("throttle") {
                        stereotype("AutosarPort") { "direction" to AutosarPortDirection.Provided }
                    }
                }

                interfaceOf("ThrottleControl") {
                    stereotype("ComInterface") {
                        "version" to "2.1"
                        "isService" to true
                    }
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
                                    origin = Point(x = 20f + i * 250f, y = 20f),
                                    size = Size(width = 200f, height = 120f),
                                ),
                        )
                }
            val nodes = nodeEntries.toMap()

            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 520f, height = 180f),
                nodes = nodes,
                edges = emptyMap(),
                groups = emptyMap(),
            )
        }

        // ── Test 1: EngineController renders «SoftwareComponent» with component shape

        test("SVG contains SoftwareComponent stereotype label for EngineController") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«SoftwareComponent»"
            svg shouldContain "EngineController"
        }

        // ── Test 2: ThrottleControl renders «ComInterface» with interface shape ──

        test("SVG contains ComInterface stereotype label for ThrottleControl") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "«ComInterface»"
            svg shouldContain "ThrottleControl"
        }

        // ── Test 3: Component shape marker present (kuml-component CSS class) ────

        test("SVG contains kuml-component CSS class for EngineController") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "kuml-component"
        }

        // ── Test 4: Interface shape marker present (kuml-interface CSS class) ────

        test("SVG contains kuml-interface CSS class for ThrottleControl") {
            val svg = KumlSvgRenderer.toSvg(diagram, buildLayout(), PlainTheme())
            svg shouldContain "kuml-interface"
        }

        // ── Test 5: Diagram has 2 elements (1 component + 1 interface) ───────────

        test("Engine Control diagram has 2 top-level elements (component + interface)") {
            diagram.elements.size shouldBe 2
        }

        // ── Test 6: Ports are created on the component model ─────────────────────

        test("EngineController component has 2 ports with AutosarPort stereotype applied") {
            val component =
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlComponent>()
                    .first { it.name == "EngineController" }
            component.ports.size shouldBe 2
            val portNames = component.ports.map { it.name }.toSet()
            assert("rpmSensor" in portNames) { "Expected rpmSensor port" }
            assert("throttle" in portNames) { "Expected throttle port" }
        }

        // ── Test 7: Enum tag value on port is stored correctly ───────────────────

        test("rpmSensor port has AutosarPortDirection.Required applied as enum tag") {
            val component =
                diagram.elements
                    .filterIsInstance<dev.kuml.uml.UmlComponent>()
                    .first { it.name == "EngineController" }
            val rpmPort = component.ports.first { it.name == "rpmSensor" }
            rpmPort.appliedStereotypes.size shouldBe 1
            val app = rpmPort.appliedStereotypes.first()
            app.stereotypeName shouldBe "AutosarPort"
            val directionTag = app.tags["direction"]
            val expectedTag =
                dev.kuml.uml.TagValue.EnumVal(
                    typeName = AutosarPortDirection::class.qualifiedName!!,
                    valueName = "Required",
                )
            directionTag shouldBe expectedTag
        }
    })
