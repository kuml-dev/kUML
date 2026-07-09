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
import dev.kuml.renderer.theme.core.StereotypeTheme
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for feature-level (operation/attribute) stereotype prefix rendering (V1.1.2 Ticket 2).
 *
 * Each test asserts on SVG string output. No binary goldfiles — substring-match + structural checks.
 */
class FeatureStereotypeRenderTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun singleNodeLayout(
            id: String,
            x: Float = 10f,
            y: Float = 10f,
            w: Float = 200f,
            h: Float = 120f,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(w + 20f, h + 20f),
                nodes = mapOf(NodeId(id) to NodeLayout(bounds = Rect(Point(x, y), Size(w, h)))),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun stereoApp(name: String) = KumlStereotypeApplication(profileNamespace = "test.profile", stereotypeName = name)

        // ── Test 1: Operation with stereotype — tspan prefix present ──────────

        test("operation with stereotype renders «PersistenceContext» tspan prefix in body text") {
            val op =
                UmlOperation(
                    id = "op1",
                    name = "save",
                    appliedStereotypes = listOf(stereoApp("PersistenceContext")),
                )
            val cls = UmlClass(id = "cls1", name = "Repo", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls1")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // The stereotype prefix tspan must be present
            svg shouldContain "«PersistenceContext»"
            // The tspan CSS class must be present
            svg shouldContain "kuml-feature-stereotype"
            // font-style italic must be set on the tspan
            svg shouldContain "font-style=\"italic\""
            // The operation name must also appear
            svg shouldContain "save"
        }

        // ── Test 2: Attribute with stereotype — tspan prefix present ──────────

        test("attribute with stereotype renders «Required» tspan prefix in body text") {
            val attr =
                UmlProperty(
                    id = "attr1",
                    name = "connection",
                    type = UmlTypeRef("DataSource"),
                    appliedStereotypes = listOf(stereoApp("Required")),
                )
            val cls = UmlClass(id = "cls2", name = "Service", attributes = listOf(attr))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls2")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Required»"
            svg shouldContain "kuml-feature-stereotype"
            svg shouldContain "font-style=\"italic\""
            // Attribute name still present in formatted text
            svg shouldContain "connection"
        }

        // ── Test 3: showFeatureStereotypes = false → no prefix in SVG ────────

        test("showFeatureStereotypes = false suppresses tspan prefix entirely") {
            val op =
                UmlOperation(
                    id = "op3",
                    name = "save",
                    appliedStereotypes = listOf(stereoApp("PersistenceContext")),
                )
            val cls = UmlClass(id = "cls3", name = "Repo", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls3")

            val themeOff =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(showFeatureStereotypes = false),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, layout, themeOff)

            // No stereotype prefix when toggle is off
            svg shouldNotContain "kuml-feature-stereotype"
            // The operation name must still be present
            svg shouldContain "save"
        }

        // ── Test 4: Multiple stereotypes joined by joinSeparator ──────────────

        test("multiple stereotypes on operation are joined with joinSeparator") {
            val op =
                UmlOperation(
                    id = "op4",
                    name = "process",
                    appliedStereotypes =
                        listOf(
                            stereoApp("Transactional"),
                            stereoApp("Audited"),
                        ),
                )
            val cls = UmlClass(id = "cls4", name = "PaymentService", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls4")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // Default joinSeparator is ", "
            svg shouldContain "«Transactional, Audited»"
        }

        // ── Test 5: Custom joinSeparator ──────────────────────────────────────

        test("custom joinSeparator changes multi-stereotype separator on feature line") {
            val op =
                UmlOperation(
                    id = "op5",
                    name = "process",
                    appliedStereotypes =
                        listOf(
                            stereoApp("Transactional"),
                            stereoApp("Audited"),
                        ),
                )
            val cls = UmlClass(id = "cls5", name = "PaymentService", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls5")

            val themeWithPipe =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(joinSeparator = " | "),
                )
            val svg = KumlSvgRenderer.toSvg(diagram, layout, themeWithPipe)

            svg shouldContain "«Transactional | Audited»"
            svg shouldNotContain "«Transactional, Audited»"
        }

        // ── Test 6: Backward compat — operation without stereotypes unchanged ─

        test("operation without stereotypes renders without any feature-stereotype markup") {
            val op =
                UmlOperation(
                    id = "op6",
                    name = "find",
                    // No appliedStereotypes — default empty
                )
            val cls = UmlClass(id = "cls6", name = "Repository", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls6")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // No feature-stereotype markup should appear
            svg shouldNotContain "kuml-feature-stereotype"
            // The operation body text must still be present
            svg shouldContain "find"
            svg shouldContain "kuml-body"
        }

        // ── Test 7: Interface attribute with stereotype ────────────────────────

        test("interface attribute with stereotype renders tspan prefix") {
            val attr =
                UmlProperty(
                    id = "attr7",
                    name = "timeout",
                    type = UmlTypeRef("Duration"),
                    visibility = Visibility.PUBLIC,
                    appliedStereotypes = listOf(stereoApp("ConfigProperty")),
                )
            val iface = UmlInterface(id = "iface7", name = "Configurable", attributes = listOf(attr))
            val diagram = KumlDiagram(name = "D", elements = listOf(iface))
            val layout = singleNodeLayout("iface7")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«ConfigProperty»"
            svg shouldContain "kuml-feature-stereotype"
            svg shouldContain "timeout"
        }

        // ── Test 8: Stereotype tspan appears before the feature name ──────────

        test("stereotype tspan appears before the feature name in SVG document order") {
            val op =
                UmlOperation(
                    id = "op8",
                    name = "execute",
                    appliedStereotypes = listOf(stereoApp("Scheduled")),
                )
            val cls = UmlClass(id = "cls8", name = "Job", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls8")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            val stereoPos = svg.indexOf("«Scheduled»")
            val namePos = svg.indexOf("execute")
            // stereotype prefix must appear before the feature name
            (stereoPos < namePos) shouldBe true
        }

        // ── Test 9: plain display-label stereotype on attribute (ADR-0017) ────

        test("attribute with plain stereotypes (no profile) renders «Column» tspan prefix") {
            val attr =
                UmlProperty(
                    id = "attr9",
                    name = "name",
                    type = UmlTypeRef("String"),
                    stereotypes = listOf("Column"),
                )
            val cls = UmlClass(id = "cls9", name = "User", attributes = listOf(attr))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls9")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Column»"
            svg shouldContain "kuml-feature-stereotype"
            svg shouldContain "name"
        }

        // ── Test 10: plain display-label stereotype on operation (ADR-0017) ───

        test("operation with plain stereotypes (no profile) renders tspan prefix") {
            val op =
                UmlOperation(
                    id = "op10",
                    name = "save",
                    stereotypes = listOf("Transactional"),
                )
            val cls = UmlClass(id = "cls10", name = "Repo", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls10")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "«Transactional»"
            svg shouldContain "kuml-feature-stereotype"
        }

        // ── Test 11: applied + plain stereotypes merge and dedupe on a feature ─

        test("attribute with both applied and plain stereotypes merges them, deduplicated") {
            val attr =
                UmlProperty(
                    id = "attr11",
                    name = "id",
                    type = UmlTypeRef("Long"),
                    stereotypes = listOf("Id", "PersistenceContext"),
                    appliedStereotypes = listOf(stereoApp("PersistenceContext")),
                )
            val cls = UmlClass(id = "cls11", name = "User", attributes = listOf(attr))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls11")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // Applied name comes first, then remaining plain names; duplicate "PersistenceContext" collapsed once
            svg shouldContain "«PersistenceContext, Id»"
        }
    })
