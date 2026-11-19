package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Nacharbeit 115 baseline: the geometry of the self-contained fixtures WITHOUT any layer hint. The numbers were recorded
 * on the code state before layer hints were honoured for class, component and deployment diagrams and must never change
 * with the matrix change (diagrams without `wrapColumns` / `sameLayer` / `first` / `last` stay byte-for-byte the same).
 * Structural pins (rounded origins and sizes), no SVG bytes, no fonts.
 */
class CompoundLayerBaselineTest :
    FunSpec({
        val classNodes = "M1=242,44 M2=242,239 M3=228,434 M4=428,434 M5=28,434 M6=628,434 X1=42,44 X2=442,44 P1=852,142 P2=852,337"
        val classGroups = "C1=16,16,764,490 C2=840,114,164,295"
        val deploymentNodes = "M1=244,66 M2=244,261 M3=230,456 M4=430,456 M5=30,456 M6=630,456 X1=44,66 X2=444,66 P1=858,164 P2=858,359"
        val deploymentGroups = "C1=16,16,768,514 C2=844,114,168,319"

        fun nodesOf(r: LayoutResult): String =
            r.nodes.entries.joinToString(" ") { (id, n) -> "${id.value}=${Math.round(n.bounds.origin.x)},${Math.round(n.bounds.origin.y)}" }

        fun groupsOf(r: LayoutResult): String =
            listOf("C1", "C2").joinToString(" ") {
                val b = r.groups.getValue(GroupId(it)).bounds
                "$it=${Math.round(b.origin.x)},${Math.round(b.origin.y)},${Math.round(b.size.width)},${Math.round(b.size.height)}"
            }

        CompoundLayerFixtures.Flavour.values().forEach { flavour ->
            val deployment = flavour == CompoundLayerFixtures.Flavour.DEPLOYMENT
            val expectedNodes = if (deployment) deploymentNodes else classNodes
            val expectedGroups = if (deployment) deploymentGroups else classGroups

            test("$flavour: no spec - node origins, group bounds and edge routes are unchanged, no findings") {
                val outcome =
                    CompoundLayerFixtures.run(
                        flavour = flavour,
                        graph = CompoundLayerFixtures.selfContained(flavour),
                        spec = null,
                    )
                nodesOf(outcome.result) shouldBe expectedNodes
                groupsOf(outcome.result) shouldBe expectedGroups
                outcome.result.edges.size shouldBe 6
                outcome.findings.shouldBeEmpty()
            }

            test("$flavour: an empty spec and a direction-only spec report nothing and an empty spec lays out like no spec") {
                val g = CompoundLayerFixtures.selfContained(flavour)
                val empty = CompoundLayerFixtures.run(flavour = flavour, graph = g, spec = LayoutSpec())
                nodesOf(empty.result) shouldBe expectedNodes
                groupsOf(empty.result) shouldBe expectedGroups
                val dir = CompoundLayerFixtures.run(flavour = flavour, graph = g, spec = LayoutSpec(direction = LayoutFlow.Right))
                dir.findings.filter { it.code == LayoutCodes.NO_EFFECT }.shouldBeEmpty()
            }

            test("$flavour: withMemberLayerHints returns the identical graph when the spec has no layer hint") {
                val g = CompoundLayerFixtures.selfContained(flavour)
                g.withMemberLayerHints(null) shouldBeSameInstanceAs g
                g.withMemberLayerHints(LayoutSpec()) shouldBeSameInstanceAs g
                g.withMemberLayerHints(LayoutSpec(direction = LayoutFlow.Right)) shouldBeSameInstanceAs g
                g.groups.none { it.memberLayerHints } shouldBe true
            }
        }
    })
