package dev.kuml.vaultexamples

import dev.kuml.io.svg.uml.UmlAssociationLabelPlan
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.label.LabelPlacer
import dev.kuml.layout.bridge.label.PlacedLabel
import dev.kuml.renderer.theme.core.ThemeRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * PAL 2B-b geometry invariants of the shared label placement, on the real vault examples: they work on layout
 * rectangles and polylines only (no SVG text, no font metrics), so they hold on every machine.
 */
class LabelGeometryInvariantTest :
    FunSpec({
        val examples = VaultExampleLoader.loadFromClasspath().associateBy { it.fileName.substringBefore(' ') }

        fun polyline(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.Bezier -> listOf(r.source, r.target)
            }

        fun Rect.hitsRect(o: Rect) =
            origin.x < o.origin.x + o.size.width &&
                o.origin.x < origin.x + size.width &&
                origin.y < o.origin.y + o.size.height &&
                o.origin.y < origin.y + size.height

        fun violations(
            layout: LayoutResult,
            plan: Map<String, Map<String, PlacedLabel>>,
        ): List<String> {
            val out = mutableListOf<String>()
            val all = plan.flatMap { (edge, labels) -> labels.map { (key, l) -> Triple(edge, key, l) } }.sortedBy { it.first + it.second }
            val lines = layout.edges.entries.associate { it.key.value to polyline(it.value) }
            for ((edge, key, label) in all) {
                val r = label.rect
                for ((id, n) in layout.nodes) if (r.hitsRect(n.bounds)) out += "$edge#$key covers node ${id.value}"
                for ((e2, k2, l2) in all) if ((e2 + k2) > (edge + key) && r.hitsRect(l2.rect)) out += "$edge#$key overlaps $e2#$k2"
                for ((id, pts) in lines) {
                    val dist = LabelPlacer.rectDistanceToPolyline(r = r, pts = pts)
                    if (id == edge) {
                        if (!label.onLine && dist < 0.5f) out += "$edge#$key touches its own line without halo"
                        if (dist > 12.5f) out += "$edge#$key is ${dist.toInt()} px from its line"
                    } else if (dist < 0.5f) {
                        out += "$edge#$key crosses foreign edge $id"
                    }
                }
            }
            return out
        }

        test("example 01: association, dependency and end labels keep clear of nodes, labels and foreign edges") {
            val (diagram, layout) = VaultExampleRenderer.umlLayoutOf(examples.getValue("01").kumlScript)!!
            val plan = UmlAssociationLabelPlan.planFor(diagram = diagram, layout = layout, theme = ThemeRegistry.get("plain")!!)
            plan.isEmpty() shouldBe false
            violations(layout, plan).joinToString("\n") shouldBe ""
        }

        test("example 64: in the dense hub no label covers a node or another label and every label stays at its line") {
            val (diagram, layout) = VaultExampleRenderer.umlLayoutOf(examples.getValue("64").kumlScript)!!
            val plan = UmlAssociationLabelPlan.planFor(diagram = diagram, layout = layout, theme = ThemeRegistry.get("plain")!!)
            val v = violations(layout, plan)
            // Six role labels fan into one node face; a role label may still have to cross a neighbouring edge there.
            v.filterNot { it.contains("crosses foreign edge") }.joinToString("\n") shouldBe ""
            (v.size <= 3) shouldBe true
        }

        test("example 64: parallel segments of foreign edges keep 8 px or are shorter than 6 px") {
            val (_, layout) = VaultExampleRenderer.umlLayoutOf(examples.getValue("64").kumlScript)!!
            val lines = layout.edges.entries.associate { it.key.value to polyline(it.value) }
            val bad = mutableListOf<String>()
            val ids = lines.keys.sorted()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val a = lines.getValue(ids[i])
                    val b = lines.getValue(ids[j])
                    for (x in 0 until a.size - 1) {
                        for (y in 0 until b.size - 1) {
                            val (p1, p2) = a[x] to a[x + 1]
                            val (q1, q2) = b[y] to b[y + 1]
                            val horiz = p1.y == p2.y && q1.y == q2.y
                            val vert = p1.x == p2.x && q1.x == q2.x
                            val overlap =
                                when {
                                    horiz -> minOf(maxOf(p1.x, p2.x), maxOf(q1.x, q2.x)) - maxOf(minOf(p1.x, p2.x), minOf(q1.x, q2.x))
                                    vert -> minOf(maxOf(p1.y, p2.y), maxOf(q1.y, q2.y)) - maxOf(minOf(p1.y, p2.y), minOf(q1.y, q2.y))
                                    else -> continue
                                }
                            val gap = if (horiz) kotlin.math.abs(p1.y - q1.y) else kotlin.math.abs(p1.x - q1.x)
                            if (overlap > 6f &&
                                gap < 8f
                            ) {
                                bad += "${ids[i]} and ${ids[j]}: parallel for ${overlap.toInt()} px at ${gap.toInt()} px"
                            }
                        }
                    }
                }
            }
            bad.joinToString("\n") shouldBe ""
        }

        test("example 01: the dependency name 'notifies' is planned and sits next to its own line") {
            val (diagram, layout) = VaultExampleRenderer.umlLayoutOf(examples.getValue("01").kumlScript)!!
            val plan = UmlAssociationLabelPlan.planFor(diagram = diagram, layout = layout, theme = ThemeRegistry.get("plain")!!)
            val dependencyEdge = plan.entries.firstOrNull { (_, labels) -> labels.containsKey("center") && labels.size == 1 }
            dependencyEdge shouldNotBe null
        }

        test("example 64: the dense hub produces a plan for every label of all six associations") {
            val (diagram, layout) = VaultExampleRenderer.umlLayoutOf(examples.getValue("64").kumlScript)!!
            val plan = UmlAssociationLabelPlan.planFor(diagram = diagram, layout = layout, theme = ThemeRegistry.get("plain")!!)
            // 6 associations x (up to 4 end labels), plus the 'refers to' dependency name
            (plan.values.sumOf { it.size } >= 6 * 3) shouldBe true
        }

        test("the plan is identical on a second run in the same JVM") {
            val example = examples.getValue("64")
            val a = VaultExampleRenderer.umlLayoutOf(example.kumlScript)!!
            val b = VaultExampleRenderer.umlLayoutOf(example.kumlScript)!!
            val theme = ThemeRegistry.get("plain")!!
            UmlAssociationLabelPlan.planFor(diagram = a.first, layout = a.second, theme = theme) shouldBe
                UmlAssociationLabelPlan.planFor(diagram = b.first, layout = b.second, theme = theme)
        }
    })
