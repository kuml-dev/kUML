package dev.kuml.layout.bridge

import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** PAL wave E1 (13a): blocks of one grid row of a wrapped BDD share the same top edge. */
class BddWrapColumnsTopAlignTest :
    FunSpec({
        timeout = 30_000L

        test("every block of a grid row has the same top edge (0.5 px)") {
            val m =
                sysml2Model(name = "Rows") {
                    for (i in 1..6) {
                        partDef(name = "P$i") {
                            // Different heights: block i has i attributes.
                            for (a in 1..i) attribute(name = "a$a", typeId = "Real")
                        }
                    }
                    bdd(name = "BDD") {
                        layout { this.wrapColumns = 3 }
                        for (i in 1..6) includeById(id = "P$i")
                    }
                }
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            val r =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d),
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_BDD,
                    ).result
            val boxes = r.nodes.values.map { it.bounds }
            var pairs = 0
            for (a in boxes) {
                for (b in boxes) {
                    if (a === b) continue
                    val overlap = minOf(a.origin.y + a.size.height, b.origin.y + b.size.height) - maxOf(a.origin.y, b.origin.y)
                    if (overlap > 0.5f * minOf(a.size.height, b.size.height)) {
                        pairs++
                        (abs(a.origin.y - b.origin.y) <= 0.5f) shouldBe true
                    }
                }
            }
            (pairs > 0) shouldBe true
        }
    })
