package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.CompoundLayerFixtures.Flavour
import dev.kuml.layout.bridge.CompoundLayerFixtures.boundsOf
import dev.kuml.layout.bridge.CompoundLayerFixtures.members
import dev.kuml.layout.bridge.CompoundLayerFixtures.nestedDeployment
import dev.kuml.layout.bridge.CompoundLayerFixtures.run
import dev.kuml.layout.bridge.CompoundLayerFixtures.y
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs

/**
 * Nacharbeit 119: layer hints act in a group that contains nested groups when the whole subtree is self-contained. Real
 * nesting exists in the bridge for deployment nodes only (class and component diagrams flatten sub-packages into
 * sibling groups). Structural assertions only: relative positions, containment, ordering; fixed node sizes, no fonts.
 */
class CompoundLayerNestedHintsTest :
    FunSpec({
        val f = Flavour.DEPLOYMENT
        val tol = 0.5f

        fun w001(o: SpecLayoutOutcome) = o.findings.filter { it.code == LayoutCodes.NO_EFFECT }

        fun bounds(
            r: LayoutResult,
            group: String,
        ): Rect = r.groups.getValue(GroupId(group)).bounds

        fun nodeBounds(
            r: LayoutResult,
            id: String,
        ): Rect = r.nodes.getValue(NodeId(id)).bounds

        fun contains(
            outer: Rect,
            inner: Rect,
        ) = inner.origin.x >= outer.origin.x - tol &&
            inner.origin.y >= outer.origin.y - tol &&
            inner.origin.x + inner.size.width <= outer.origin.x + outer.size.width + tol &&
            inner.origin.y + inner.size.height <= outer.origin.y + outer.size.height + tol

        fun overlap(
            a: Rect,
            b: Rect,
        ) = a.origin.x < b.origin.x + b.size.width - tol &&
            a.origin.x + a.size.width > b.origin.x + tol &&
            a.origin.y < b.origin.y + b.size.height - tol &&
            a.origin.y + a.size.height > b.origin.y + tol

        fun maxPerRow(
            r: LayoutResult,
            ids: List<String>,
        ): Int =
            ids
                .groupBy { Math.round(y(r = r, id = it) / 4f) }
                .values
                .maxOf { it.size }

        fun sameRow(
            r: LayoutResult,
            a: String,
            b: String,
        ) = abs(y(r = r, id = a) - y(r = r, id = b)) < tol

        test("a nested node does not block wrapColumns: direct members in rows of two, N1 a closed box inside C1") {
            val g = nestedDeployment()
            val plain = run(flavour = f, graph = g, spec = null)
            val wrap = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
            w001(wrap).shouldBeEmpty()
            boundsOf(result = wrap.result, ids = members) shouldNotBe boundsOf(result = plain.result, ids = members)
            maxPerRow(r = wrap.result, ids = members) shouldBeLessThanOrEqualTo 2
            val n1 = bounds(r = wrap.result, group = "N1")
            contains(bounds(r = wrap.result, group = "C1"), n1) shouldBe true
            for (id in members) overlap(nodeBounds(r = wrap.result, id = id), n1) shouldBe false
            for (id in listOf("Q1", "Q2", "Q3")) contains(n1, nodeBounds(r = wrap.result, id = id)) shouldBe true
            wrap.result.nodes.keys shouldBe g.nodes.map { it.id }.toSet()
            wrap.result.edges.size shouldBe g.edges.size
        }

        test("both levels honoured: sameLayer in C1 and first inside N1") {
            val g = nestedDeployment()
            val r = run(flavour = f, graph = g, spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1")), first = listOf("Q3"))).result
            sameRow(r = r, a = "M3", b = "X1") shouldBe true
            for (id in listOf("Q1", "Q2")) (y(r = r, id = "Q3") <= y(r = r, id = id) + tol) shouldBe true
            val outcome = run(flavour = f, graph = g, spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1")), first = listOf("Q3")))
            w001(outcome).shouldBeEmpty()
        }

        test("hints only inside the nested node (its parent has none) are honoured") {
            val g = nestedDeployment(extra = listOf(Triple("aq21", "Q2", "Q1")))
            val outcome = run(flavour = f, graph = g, spec = LayoutSpec(first = listOf("Q3"), last = listOf("Q1")))
            w001(outcome).shouldBeEmpty()
            val r = outcome.result
            (y(r = r, id = "Q3") <= y(r = r, id = "Q2") + tol) shouldBe true
            (y(r = r, id = "Q2") < y(r = r, id = "Q1")) shouldBe true
            contains(bounds(r = r, group = "C1"), bounds(r = r, group = "N1")) shouldBe true
        }

        test("three levels deep: wrapColumns honoured, K1 inside N1 inside C1") {
            val g = nestedDeployment(deep = true)
            val outcome = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
            w001(outcome).shouldBeEmpty()
            val r = outcome.result
            maxPerRow(r = r, ids = members) shouldBeLessThanOrEqualTo 2
            contains(bounds(r = r, group = "C1"), bounds(r = r, group = "N1")) shouldBe true
            contains(bounds(r = r, group = "N1"), bounds(r = r, group = "K1")) shouldBe true
            for (id in listOf("R1", "R2")) contains(bounds(r = r, group = "K1"), nodeBounds(r = r, id = id)) shouldBe true
        }

        test("a relationship from a nested member to a direct member: W001 names the nested group, members stay in place") {
            val g = nestedDeployment(extra = listOf(Triple("aq", "Q1", "M1")))
            val plain = run(flavour = f, graph = g, spec = null)
            val wrap = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
            boundsOf(result = wrap.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
            w001(wrap) shouldHaveSize 1
            w001(wrap).single().message shouldContain "wrap_columns"
            w001(wrap).single().message shouldContain "'aq'"
            w001(wrap).single().message shouldContain "border of nested group 'N1'"
            val same = run(flavour = f, graph = g, spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))))
            w001(same).single().message shouldContain "border of nested group 'N1'"
        }

        test("a relationship across the border of the outer node itself: W001 says it crosses its border") {
            val g = nestedDeployment(extra = listOf(Triple("ao", "M1", "O1")), outside = true)
            val wrap = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
            w001(wrap) shouldHaveSize 1
            w001(wrap).single().message shouldContain "'C1'"
            w001(wrap).single().message shouldContain "crosses its border"
            w001(wrap).single().message shouldNotContain "nested group"
        }

        test("a hint that addresses the id of the nested group is not supported and does not throw") {
            val g = nestedDeployment()
            val outcome = run(flavour = f, graph = g, spec = LayoutSpec(first = listOf("N1")))
            outcome.result.groups.keys
                .map { it.value }
                .toSet() shouldBe setOf("C1", "N1")
        }

        test("without a spec the nested diagram is laid out as before and reports nothing") {
            val g = nestedDeployment()
            val a = run(flavour = f, graph = g, spec = null)
            val b = run(flavour = f, graph = g, spec = LayoutSpec())
            a.findings.shouldBeEmpty()
            boundsOf(result = a.result, ids = members + listOf("Q1", "Q2", "Q3")) shouldBe
                boundsOf(result = b.result, ids = members + listOf("Q1", "Q2", "Q3"))
        }

        test("drift: the scope of the bridge and the engine agree on which groups honour member hints") {
            val graphs =
                listOf(
                    nestedDeployment(),
                    nestedDeployment(deep = true),
                    nestedDeployment(extra = listOf(Triple("aq", "Q1", "M1"))),
                    nestedDeployment(extra = listOf(Triple("ao", "M1", "O1")), outside = true),
                    CompoundLayerFixtures.selfContained(f),
                    CompoundLayerFixtures.crossing(f),
                )
            for (g in graphs) {
                val scope = CompoundLayerScope.of(g)
                val outcome = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
                // W001 appears exactly when some group does not honour the hints.
                w001(outcome).isEmpty() shouldBe scope.members.keys.all { scope.honours(it) }
                // Where every group honours the hints, the engine never warns that a layer hint is ignored in a group.
                if (scope.members.keys.all { scope.honours(it) }) {
                    outcome.result.warnings
                        .filter { it.code == "hint.ignored.layerInCompound" }
                        .shouldBeEmpty()
                }
            }
        }
    })
