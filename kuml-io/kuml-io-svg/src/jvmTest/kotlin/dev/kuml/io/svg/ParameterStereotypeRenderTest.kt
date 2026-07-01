package dev.kuml.io.svg

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.uml.formatParameter
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
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * V1.1.3 Ticket 3 — Parameter-level stereotype rendering as inline prefix
 * `«Stereo» name: Type` inside the operation signature.
 */
class ParameterStereotypeRenderTest :
    FunSpec({

        fun singleNodeLayout(
            id: String,
            x: Float = 10f,
            y: Float = 10f,
            w: Float = 240f,
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

        fun stereoApp(name: String) =
            KumlStereotypeApplication(
                profileNamespace = "test.profile",
                stereotypeName = name,
            )

        // ── Test 1 — Parameter without stereotype: plain `name: Type` ─────────

        test("parameter without stereotype formats as name: Type") {
            val p =
                UmlParameter(
                    id = "p1",
                    name = "email",
                    type = UmlTypeRef("String"),
                    direction = ParameterDirection.IN,
                )
            p.formatParameter(PlainTheme()) shouldBe "email: String"
        }

        // ── Test 2 — Parameter with single stereotype gets inline prefix ──────

        test("parameter with single stereotype gets inline «Stereo» prefix") {
            val p =
                UmlParameter(
                    id = "p2",
                    name = "email",
                    type = UmlTypeRef("String"),
                    direction = ParameterDirection.IN,
                    appliedStereotypes = listOf(stereoApp("Parameter")),
                )
            p.formatParameter(PlainTheme()) shouldBe "«Parameter» email: String"
        }

        // ── Test 3 — Multiple stereotypes joined by joinSeparator ─────────────

        test("multiple parameter stereotypes joined by joinSeparator") {
            val p =
                UmlParameter(
                    id = "p3",
                    name = "x",
                    type = UmlTypeRef("Int"),
                    direction = ParameterDirection.IN,
                    appliedStereotypes = listOf(stereoApp("A"), stereoApp("B")),
                )
            // Default joinSeparator is ", "
            p.formatParameter(PlainTheme()) shouldBe "«A, B» x: Int"

            val themeWithPipe =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(joinSeparator = " | "),
                )
            p.formatParameter(themeWithPipe) shouldBe "«A | B» x: Int"
        }

        // ── Test 4 — showFeatureStereotypes = false hides parameter prefix ────

        test("showFeatureStereotypes=false hides parameter stereotype prefix") {
            val p =
                UmlParameter(
                    id = "p4",
                    name = "email",
                    type = UmlTypeRef("String"),
                    direction = ParameterDirection.IN,
                    appliedStereotypes = listOf(stereoApp("Parameter")),
                )
            val themeOff =
                PlainTheme().copy(
                    stereotypes = StereotypeTheme(showFeatureStereotypes = false),
                )
            p.formatParameter(themeOff) shouldBe "email: String"
        }

        // ── Test 5 — End-to-end: operation parameter shows in SVG ─────────────

        test("operation renders parameter stereotype inline in SVG body text") {
            val param =
                UmlParameter(
                    id = "param5",
                    name = "email",
                    type = UmlTypeRef("String"),
                    direction = ParameterDirection.IN,
                    appliedStereotypes = listOf(stereoApp("Parameter")),
                )
            val op =
                UmlOperation(
                    id = "op5",
                    name = "findByEmail",
                    parameters = listOf(param),
                )
            val cls = UmlClass(id = "cls5", name = "UserRepository", operations = listOf(op))
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val layout = singleNodeLayout("cls5")

            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            // The parameter-level stereotype must appear inline inside the operation signature.
            svg shouldContain "«Parameter» email: String"
            // The operation name is also there
            svg shouldContain "findByEmail"
        }
    })
