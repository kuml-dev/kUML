package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.GroupId
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Rect
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

private object WelleC2NoEngine : KumlLayoutEngine {
    override val id = LayoutEngineId("none")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.Generic),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
            supportsLayerHints = false,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult = error("the engine must not be called when the group grid applies")
}

private class WelleC2Laid(
    val result: LayoutResult,
    val findings: List<String>,
) {
    fun frame(name: String): Rect = result.groups.getValue(GroupId("group:$name")).bounds
}

private fun laidC2(model: Sysml2Model): WelleC2Laid {
    val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
    val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
    val outcome =
        LayoutSpecRunner.layout(
            engine = WelleC2NoEngine,
            graph = graph,
            hints = LayoutHints(),
            spec = diagram.layout,
            kind = SpecDiagramKind.SYSML2_REQ,
        )
    return WelleC2Laid(result = outcome.result, findings = outcome.findings.map { it.code })
}

/** PAL Welle C2 - top-level column choice, first/last pins and the two new findings. Geometry only. */
class ReqWelleC2Test :
    FunSpec({
        /** Three top-level groups, the middle one holds a sub group of five cards with the given local wrapColumns. */
        fun nested(
            localColumns: Int?,
            diagram: (dev.kuml.sysml2.dsl.Sysml2LayoutBuilder.() -> Unit)? = null,
        ) = sysml2Model(name = "M") {
            val a = (1..2).map { requirementDef(name = "A$it", text = "a$it") }
            val b = (1..5).map { requirementDef(name = "B$it", text = "b$it") }
            val c = (1..2).map { requirementDef(name = "C$it", text = "c$it") }
            reqDiagram(name = "D") {
                group(name = "First") { a.forEach { include(it) } }
                group(name = "Second") {
                    group(name = "Inner") {
                        b.forEach { include(it) }
                        if (localColumns != null) layout { wrapColumns = localColumns }
                    }
                }
                group(name = "Third") { c.forEach { include(it) } }
                if (diagram != null) layout(diagram)
            }
        }

        test("without a diagram setting the top-level groups stand in one column, whatever a nested wrapColumns is") {
            for (cols in listOf(2, 3, 5)) {
                val t = laidC2(nested(localColumns = cols))
                val f = listOf("First", "Second", "Third").map { t.frame(it) }
                f.map { it.origin.x }.toSet().size shouldBe 1
                (f[1].origin.y > f[0].origin.y + f[0].size.height) shouldBe true
                (f[2].origin.y > f[1].origin.y + f[1].size.height) shouldBe true
            }
        }

        test("the top-level column does not depend on a nested wrapColumns") {
            fun xs(cols: Int) =
                laidC2(nested(localColumns = cols)).let { t -> listOf("First", "Second", "Third").map { t.frame(it).origin.x } }
            xs(2) shouldBe xs(3)
            xs(3) shouldBe xs(5)
        }

        test("with aspectRatio the column count is still chosen by the aspect target") {
            val t = laidC2(nested(localColumns = 2) { aspectRatio = 4.0 })
            listOf("First", "Second", "Third").map { t.frame(it).origin.x }.toSet().size shouldBe 3
        }

        test("an explicit diagram wrapColumns still counts") {
            val t = laidC2(nested(localColumns = 2) { wrapColumns = 2 })
            val f = listOf("First", "Second", "Third").map { t.frame(it).origin }
            f[0].y shouldBe f[1].y
            (f[2].y > f[0].y) shouldBe true
        }

        test("first and last pin top-level groups to the first and last grid cell") {
            val model =
                sysml2Model(name = "M") {
                    val r = (1..3).map { requirementDef(name = "R$it", text = "r$it") }
                    reqDiagram(name = "D") {
                        val a = group(name = "A") { include(r[0]) }
                        group(name = "B") { include(r[1]) }
                        val c = group(name = "C") { include(r[2]) }
                        layout {
                            first(c)
                            last(a)
                        }
                    }
                }
            val t = laidC2(model)
            val y = listOf("C", "B", "A").map { t.frame(it).origin.y }
            (y[0] < y[1]) shouldBe true
            (y[1] < y[2]) shouldBe true
            t.findings shouldNotContain LayoutCodes.FIRST_LAST_NESTED_GROUP_IGNORED
        }

        test("first on a nested group is ignored with W011") {
            val model =
                sysml2Model(name = "M") {
                    val r = (1..3).map { requirementDef(name = "R$it", text = "r$it") }
                    reqDiagram(name = "D") {
                        group(name = "A") { include(r[0]) }
                        group(name = "B") {
                            include(r[1])
                            group(name = "Deep") { include(r[2]) }
                        }
                        layout { firstById("group:B/Deep") }
                    }
                }
            laidC2(model).findings shouldContain LayoutCodes.FIRST_LAST_NESTED_GROUP_IGNORED
        }

        test("sameLayer with element ids at a group grid is ignored with W010") {
            val model =
                sysml2Model(name = "M") {
                    val r = (1..2).map { requirementDef(name = "R$it", text = "r$it") }
                    reqDiagram(name = "D") {
                        group(name = "A") { include(r[0]) }
                        group(name = "B") { include(r[1]) }
                        layout {
                            wrapColumns = 2
                            sameLayerById("R1", "R2")
                        }
                    }
                }
            laidC2(model).findings shouldContain LayoutCodes.SAME_LAYER_ACROSS_GROUPS_IGNORED
        }

        test("sameLayer of top-level groups gives no W010") {
            val model =
                sysml2Model(name = "M") {
                    val r = (1..2).map { requirementDef(name = "R$it", text = "r$it") }
                    reqDiagram(name = "D") {
                        val a = group(name = "A") { include(r[0]) }
                        val b = group(name = "B") { include(r[1]) }
                        layout { sameLayer(a, b) }
                    }
                }
            laidC2(model).findings shouldNotContain LayoutCodes.SAME_LAYER_ACROSS_GROUPS_IGNORED
        }
    })
