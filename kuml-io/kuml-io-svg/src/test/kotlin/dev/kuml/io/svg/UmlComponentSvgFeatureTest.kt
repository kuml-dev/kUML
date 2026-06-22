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
import dev.kuml.uml.UmlComponent
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
    })

private infix fun Int.shouldBeAtLeast(min: Int) {
    if (this < min) {
        throw AssertionError("Expected at least $min, got $this")
    }
}
