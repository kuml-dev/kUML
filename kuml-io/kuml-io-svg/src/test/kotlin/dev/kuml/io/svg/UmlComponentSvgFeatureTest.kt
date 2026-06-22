package dev.kuml.io.svg

import dev.kuml.core.model.DiagramType
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
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPort
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * V1.1.3 Ticket 4 — component compartment rendering.
 *
 * Backward-compat: components without features render exactly as before
 * (no extra `kuml-divider` lines).
 */
class UmlComponentSvgFeatureTest :
    FunSpec({

        fun singleNodeLayout(id: String): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(280f, 200f),
                nodes =
                    mapOf(
                        NodeId(id) to
                            NodeLayout(bounds = Rect(Point(10f, 10f), Size(240f, 160f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun stereoApp(name: String) =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = name,
            )

        // Count occurrences of a `<line ... class="kuml-divider"` element. The CSS
        // class definition appears in the SVG <style> block once unconditionally; we
        // count only the actual line elements.
        fun countDividerLines(svg: String): Int = Regex("""<line[^>]*class="kuml-divider"""").findAll(svg).count()

        test("component without features renders header-only without divider lines") {
            val cmp = UmlComponent(id = "c1", name = "OrderService")
            val diagram = KumlDiagram(name = "D", elements = listOf(cmp))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("c1"), PlainTheme())

            svg shouldContain "«component»"
            svg shouldContain "OrderService"
            // No divider line elements for a feature-free component
            countDividerLines(svg) shouldBe 0
        }

        test("component with one operation renders operation in compartment with divider line") {
            val op = UmlOperation(id = "c2::op", name = "doIt")
            val cmp = UmlComponent(id = "c2", name = "OrderService", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cmp))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("c2"), PlainTheme())

            svg shouldContain "doIt"
            countDividerLines(svg) shouldBeAtLeast 1
            svg shouldContain "kuml-body"
        }

        test("component with attribute+operation renders two compartments separated by dividers") {
            val attr =
                UmlProperty(
                    id = "c3::a",
                    name = "data",
                    type = UmlTypeRef("Double"),
                )
            val op = UmlOperation(id = "c3::op", name = "compute")
            val cmp =
                UmlComponent(
                    id = "c3",
                    name = "SteeringControlSWC",
                    attributes = listOf(attr),
                    operations = listOf(op),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cmp))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("c3"), PlainTheme())

            svg shouldContain "data"
            svg shouldContain "compute"
            // Two dividers expected: one between header and attributes,
            // a second between attributes and operations.
            countDividerLines(svg) shouldBeAtLeast 2
        }

        test("component with operation+stereotype renders feature stereotype prefix") {
            val op =
                UmlOperation(
                    id = "c4::op",
                    name = "computeSteeringAngle",
                    appliedStereotypes = listOf(stereoApp("Runnable")),
                )
            val cmp = UmlComponent(id = "c4", name = "SteeringControlSWC", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cmp))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("c4"), PlainTheme())

            svg shouldContain "«Runnable»"
            svg shouldContain "kuml-feature-stereotype"
            svg shouldContain "font-style=\"italic\""
            svg shouldContain "computeSteeringAngle"
        }

        // V3.x — Composite-Structure: verschachtelte Parts (nestedComponents)
        // werden als Boxen INNERHALB der äußeren Komponente gerendert.
        // Regressionsschutz gegen den Bug "OrderService rendert leer, Parts fehlen".
        test("component with nested parts renders them inside as sub-boxes") {
            val validator =
                UmlComponent(
                    id = "validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "validator::in", name = "in")),
                )
            val persistence =
                UmlComponent(
                    id = "persistence",
                    name = "Persistence",
                    ports = listOf(UmlPort(id = "persistence::out", name = "out")),
                )
            val service =
                UmlComponent(
                    id = "svc",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "svc::api", name = "api")),
                    nestedComponents = listOf(validator, persistence),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(service))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("svc"), PlainTheme())

            // Outer component + both nested parts each emit a «component» keyword.
            Regex("«component»").findAll(svg).count() shouldBe 3
            svg shouldContain "OrderService"
            svg shouldContain "Validator"
            svg shouldContain "Persistence"
            // Nested parts are drawn as their own <g id="…"> groups (proves they
            // are rendered, not silently dropped).
            svg shouldContain "id=\"validator\""
            svg shouldContain "id=\"persistence\""
            // All three ports render: parent `api` + nested `in` + nested `out`.
            Regex("class=\"kuml-port-label\"").findAll(svg).count() shouldBe 3
        }

        test("component without nested parts emits exactly one «component» keyword") {
            val cmp = UmlComponent(id = "flat", name = "FlatService")
            val diagram = KumlDiagram(name = "D", elements = listOf(cmp))
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("flat"), PlainTheme())

            Regex("«component»").findAll(svg).count() shouldBe 1
        }

        // V3.x — Port-zu-Part-Connectors: Delegation-Connector (boundary-port → nested part port)
        test("composite structure delegation connector boundary-port to nested part port renders kuml-connector line") {
            val validator =
                UmlComponent(
                    id = "Validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "Validator::in", name = "in")),
                )
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                    nestedComponents = listOf(validator),
                )
            val delegation =
                UmlConnector(
                    id = "conn::OrderService::api--Validator::in",
                    end1Id = "OrderService::api",
                    end2Id = "Validator::in",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(service, delegation),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("OrderService"), PlainTheme())

            // The internal connector is drawn as a line, NOT routed by ELK
            // (singleNodeLayout has edges = emptyMap()), proving it bypasses ELK.
            svg shouldContain "kuml-connector"
            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 1
            // Boundary port "api" is the only outer port → left side → its center x = 0 in the
            // OrderService local <g>; the line must start at x1=0 (the box left wall).
            val line = Regex("""<line ([^>]*)>""").find(svg)!!.groupValues[1]
            line shouldContain "x1=\"0\""
        }

        // V3.x — Port-zu-Part-Connectors: Assembly-Connector (part port → part port)
        test("composite structure assembly connector part-to-part renders a second kuml-connector line") {
            val validator =
                UmlComponent(
                    id = "Validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "Validator::out", name = "out")),
                )
            val persistence =
                UmlComponent(
                    id = "Persistence",
                    name = "Persistence",
                    ports = listOf(UmlPort(id = "Persistence::in", name = "in")),
                )
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    nestedComponents = listOf(validator, persistence),
                )
            val assembly =
                UmlConnector(
                    id = "conn::Validator::out--Persistence::in",
                    end1Id = "Validator::out",
                    end2Id = "Persistence::in",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(service, assembly),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("OrderService"), PlainTheme())

            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 1
            // Both nested parts still render.
            svg shouldContain "Validator"
            svg shouldContain "Persistence"
            Regex("«component»").findAll(svg).count() shouldBe 3
        }

        // V3.x — Named connector: drawInternalConnectors() emits a <text class="kuml-connector-label">
        // at the midpoint when connector.name is non-null. This test guards against regressions
        // in xmlEscapeText or midpoint arithmetic on that path.
        test("internal connector with a non-empty name emits a kuml-connector-label text element") {
            val validator =
                UmlComponent(
                    id = "Validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "Validator::in", name = "in")),
                )
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                    nestedComponents = listOf(validator),
                )
            val delegation =
                UmlConnector(
                    id = "conn::named",
                    end1Id = "OrderService::api",
                    end2Id = "Validator::in",
                    name = "delegate",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(service, delegation),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("OrderService"), PlainTheme())

            // The connector line itself is still present.
            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 1
            // And the name label is emitted.
            svg shouldContain "kuml-connector-label"
            svg shouldContain "delegate"
        }

        // V3.x — resolvePortCenter() must return null (and skip silently) when the
        // endpoint references a port name that does not exist on the component. This
        // guards against a crash / NPE on that path.
        test("internal connector referencing a non-existent port name is silently skipped — zero connector lines") {
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                    nestedComponents =
                        listOf(
                            UmlComponent(
                                id = "Validator",
                                name = "Validator",
                                // No port "in" declared — end2Id below references a ghost port.
                                ports = emptyList(),
                            ),
                        ),
                )
            val badConnector =
                UmlConnector(
                    id = "conn::bad-port",
                    end1Id = "OrderService::api",
                    // "Validator::in" cannot be resolved because Validator has no port named "in".
                    end2Id = "Validator::in",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(service, badConnector),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("OrderService"), PlainTheme())

            // resolvePortCenter() returns null for the missing port → the connector is
            // skipped entirely (continue), so exactly zero kuml-connector lines appear.
            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 0
            // The component box itself still renders normally.
            svg shouldContain "OrderService"
        }

        // Regression: a UmlComponent with NO nestedComponents but with a
        // boundary-to-boundary UmlConnector (internalConnectorsByParentId[nodeId]
        // is non-null, but element.nestedComponents is empty) must render cleanly
        // with exactly zero kuml-connector lines and must not double-draw via ELK.
        //
        // Before the layout-bridge fix, such a connector became an ELK self-edge.
        // The SVG renderer's drawComponentBox() correctly skips the connector block
        // when nestedComponents.isNotEmpty() is false — so the SVG side was already
        // silent, but the ELK side produced a spurious edge. This test verifies the
        // full end-to-end invariant: zero connector lines, component box still renders.
        //
        // Note: buildInternalConnectorIndex() does classify this connector as
        // "internal" (both nodeIds are in the flat component's own subtree), so
        // internalConnectorsByParentId[nodeId] is non-null — but drawComponentBox()
        // guards with nestedComponents.isNotEmpty() and skips the connector block.
        // The expected result is zero kuml-connector lines (silent drop), not a crash.
        test("flat component with boundary-to-boundary connector renders cleanly with zero kuml-connector lines") {
            val flatService =
                UmlComponent(
                    id = "FlatService",
                    name = "FlatService",
                    ports =
                        listOf(
                            UmlPort(id = "FlatService::in", name = "in"),
                            UmlPort(id = "FlatService::out", name = "out"),
                        ),
                    // Deliberately NO nestedComponents.
                )
            val boundaryConnector =
                UmlConnector(
                    id = "conn::flat-boundary",
                    end1Id = "FlatService::in",
                    end2Id = "FlatService::out",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(flatService, boundaryConnector),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("FlatService"), PlainTheme())

            // The flat component box still renders.
            svg shouldContain "FlatService"
            Regex("«component»").findAll(svg).count() shouldBe 1
            // The boundary-to-boundary connector is silently dropped — zero connector lines.
            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 0
        }

        // V3.x — Port-zu-Part-Connectors: no double-drawing when ELK has no edge for them
        test("internal connectors are not double-drawn when ELK provides no route for them") {
            val validator =
                UmlComponent(
                    id = "Validator",
                    name = "Validator",
                    ports = listOf(UmlPort(id = "Validator::in", name = "in")),
                )
            val service =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                    nestedComponents = listOf(validator),
                )
            val delegation =
                UmlConnector(
                    id = "conn::OrderService::api--Validator::in",
                    end1Id = "OrderService::api",
                    end2Id = "Validator::in",
                )
            val diagram =
                KumlDiagram(
                    name = "D",
                    type = DiagramType.COMPOSITE_STRUCTURE,
                    elements = listOf(service, delegation),
                )
            // singleNodeLayout intentionally has edges = emptyMap(): the ELK edge loop
            // contributes nothing, so the only line comes from the in-box renderer.
            val svg = KumlSvgRenderer.toSvg(diagram, singleNodeLayout("OrderService"), PlainTheme())
            Regex("""<line[^>]*class="kuml-connector"""").findAll(svg).count() shouldBe 1
        }
    })

private infix fun Int.shouldBeAtLeast(min: Int) {
    if (this < min) {
        throw AssertionError("Expected at least $min, got $this")
    }
}
