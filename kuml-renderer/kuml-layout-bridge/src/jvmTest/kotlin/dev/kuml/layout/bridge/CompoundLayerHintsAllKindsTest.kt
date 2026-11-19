package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.CompoundLayerFixtures.Flavour
import dev.kuml.layout.bridge.CompoundLayerFixtures.boundsOf
import dev.kuml.layout.bridge.CompoundLayerFixtures.members
import dev.kuml.layout.bridge.CompoundLayerFixtures.run
import dev.kuml.layout.bridge.CompoundLayerFixtures.y
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

/**
 * Nacharbeit 115: `wrapColumns`, `sameLayer`, `first` and `last` act on the members of a self-contained package of a class
 * or component diagram and of a deployment node that holds artifacts, exactly as for UML package diagrams
 * (Nacharbeit 107). Crossing relationships (also across the border of a nested container, Nacharbeit 119) and mixed-scope `sameLayer` groups stay reported with
 * `KUML-LAYOUT-W001`. Geometry only, fixed node sizes, no fonts.
 */
class CompoundLayerHintsAllKindsTest :
    FunSpec({
        fun w001(o: SpecLayoutOutcome) = o.findings.filter { it.code == LayoutCodes.NO_EFFECT }

        fun maxPerRow(
            r: LayoutResult,
            ids: List<String>,
        ): Int =
            ids
                .groupBy { Math.round(y(r = r, id = it) / 4f) }
                .values
                .maxOf { it.size }

        Flavour.values().forEach { f ->
            test("$f: self-contained container - wrapColumns arranges the members in rows of at most two, no W001") {
                val plain = run(flavour = f, graph = CompoundLayerFixtures.selfContained(f), spec = null)
                val wrap = run(flavour = f, graph = CompoundLayerFixtures.selfContained(f), spec = LayoutSpec(wrapColumns = 2))
                boundsOf(result = wrap.result, ids = members) shouldNotBe boundsOf(result = plain.result, ids = members)
                maxPerRow(wrap.result, members) shouldBeLessThanOrEqualTo 2
                w001(wrap).shouldBeEmpty()
            }

            test("$f: self-contained container - sameLayer puts the listed members on one row, no W001") {
                val r =
                    run(
                        flavour = f,
                        graph = CompoundLayerFixtures.selfContained(f),
                        spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))),
                    )
                (abs(y(r = r.result, id = "M3") - y(r = r.result, id = "X1")) < 0.5f) shouldBe true
                w001(r).shouldBeEmpty()
            }

            test("$f: self-contained container - first and last put members on the extreme rows") {
                val r =
                    run(
                        flavour = f,
                        graph = CompoundLayerFixtures.selfContained(f),
                        spec = LayoutSpec(first = listOf("X1"), last = listOf("X2")),
                    ).result
                (members - setOf("X1", "X2")).forEach {
                    (y(r = r, id = "X1") <= y(r = r, id = it) + 0.5f) shouldBe true
                    (y(r = r, id = "X2") >= y(r = r, id = it) - 0.5f) shouldBe true
                }
                (y(r = r, id = "X1") < y(r = r, id = "X2")) shouldBe true
            }

            test("$f: the hints keep every member inside its container and every edge present") {
                val g = CompoundLayerFixtures.selfContained(f)
                val r = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2)).result
                r.nodes.keys shouldBe g.nodes.map { it.id }.toSet()
                r.edges.size shouldBe g.edges.size
                val frame = r.groups.getValue(g.groups.first { it.id.value == "C1" }.id).bounds
                members.forEach {
                    val b = r.nodes.getValue(dev.kuml.layout.NodeId(it)).bounds
                    (b.origin.x >= frame.origin.x && b.origin.y >= frame.origin.y) shouldBe true
                    (b.origin.x + b.size.width <= frame.origin.x + frame.size.width) shouldBe true
                    (b.origin.y + b.size.height <= frame.origin.y + frame.size.height) shouldBe true
                }
            }

            test("$f: a crossing relationship keeps the members in place and W001 names the container and the relationship") {
                val plain = run(flavour = f, graph = CompoundLayerFixtures.crossing(f), spec = null)
                val wrap = run(flavour = f, graph = CompoundLayerFixtures.crossing(f), spec = LayoutSpec(wrapColumns = 2))
                boundsOf(result = wrap.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
                w001(wrap) shouldHaveSize 1
                w001(wrap).single().message shouldContain "wrap_columns"
                w001(wrap).single().message shouldContain "'C1'"
                w001(wrap).single().message shouldContain "'ax'"
                val same =
                    run(flavour = f, graph = CompoundLayerFixtures.crossing(f), spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))))
                boundsOf(result = same.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
                w001(same) shouldHaveSize 1
                w001(same).single().message shouldContain "'C1'"
            }

            if (f == Flavour.DEPLOYMENT) {
                test("$f: a node with a self-contained nested node is honoured, no W001 (Nacharbeit 119)") {
                    val plain = run(flavour = f, graph = CompoundLayerFixtures.nested(f), spec = null)
                    val wrap = run(flavour = f, graph = CompoundLayerFixtures.nested(f), spec = LayoutSpec(wrapColumns = 2))
                    boundsOf(result = wrap.result, ids = members) shouldNotBe boundsOf(result = plain.result, ids = members)
                    w001(wrap).shouldBeEmpty()
                    maxPerRow(wrap.result, members) shouldBeLessThanOrEqualTo 2
                }
            } else {
                test("$f: a sub-package is laid out as a sibling group (bridge flattens one level), it does not block the outer package") {
                    val g = CompoundLayerFixtures.nested(f)
                    g.groups.all { it.parent == null } shouldBe true
                    val plain = run(flavour = f, graph = g, spec = null)
                    val wrap = run(flavour = f, graph = g, spec = LayoutSpec(wrapColumns = 2))
                    boundsOf(result = wrap.result, ids = members) shouldNotBe boundsOf(result = plain.result, ids = members)
                    w001(wrap).shouldBeEmpty()
                }
            }

            test("$f: a sameLayer group of a member and a top-level element is a mixed-scope W001") {
                val g = CompoundLayerFixtures.selfContained(f)
                val plain = run(flavour = f, graph = g, spec = null)
                val r = run(flavour = f, graph = g, spec = LayoutSpec(sameLayer = listOf(listOf("M3", "P1"))))
                w001(r) shouldHaveSize 1
                w001(r).single().message shouldContain "mixes elements"
                // nothing is flagged: the members of C1 are not rearranged by this group
                boundsOf(result = r.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
            }

            test("$f: a sameLayer group of members of two containers is a mixed-scope W001") {
                val r =
                    run(
                        flavour = f,
                        graph = CompoundLayerFixtures.selfContained(f),
                        spec = LayoutSpec(sameLayer = listOf(listOf("M3", "P1"), listOf("M4", "P2"))),
                    )
                w001(r) shouldHaveSize 1
                w001(r).single().message shouldContain "same_layer"
            }

            test("$f: a self-contained second container reports nothing when only the first one is hinted") {
                val r =
                    run(
                        flavour = f,
                        graph = CompoundLayerFixtures.selfContained(f),
                        spec = LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))),
                    )
                w001(r).shouldBeEmpty()
            }
        }

        test("class diagram: an association class outside the package makes it non-self-contained, W001 stays") {
            val f = Flavour.CLASS
            val plain = run(flavour = f, graph = CompoundLayerFixtures.withAssociationClass(), spec = null)
            val wrap = run(flavour = f, graph = CompoundLayerFixtures.withAssociationClass(), spec = LayoutSpec(wrapColumns = 2))
            boundsOf(result = wrap.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
            w001(wrap) shouldHaveSize 1
            w001(wrap).single().message shouldContain "'C1'"
        }

        test("validate: W001 is reported only for the compound that cannot honour the hint, for all three kinds") {
            Flavour.values().forEach { f ->
                val spec = LayoutSpec(wrapColumns = 2)

                fun count(g: dev.kuml.layout.LayoutGraph) =
                    LayoutSpecRunner
                        .validate(
                            kind = f.kind,
                            spec = spec,
                            knownIds = g.nodes.map { it.id.value }.toSet(),
                            compoundMembers = true,
                            scope = CompoundLayerScope.of(g),
                            diagramName = "D",
                        ).filter { it.code == LayoutCodes.NO_EFFECT }
                count(CompoundLayerFixtures.selfContained(f)).shouldBeEmpty()
                count(CompoundLayerFixtures.crossing(f)) shouldHaveSize 1
            }
        }
    })
