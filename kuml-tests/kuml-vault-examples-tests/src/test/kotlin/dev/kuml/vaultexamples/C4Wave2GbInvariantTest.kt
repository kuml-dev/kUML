@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.hypot

/**
 * PAL wave 2G-b: font independent invariants of the C4 diagrams (relationship labels, external elements, type tags,
 * layout block). Everything is measured on the rendered SVG of the full pipeline, never on pixels.
 */
class C4Wave2GbInvariantTest :
    FunSpec({
        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        fun labels(svg: String): Map<String, SvgRect> = SvgGeometry.labelRects(svg, "kuml-c4-label").associate { it.first to it.second }

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> {
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in Regex("""<(?:line|path) [^>]*?class="kuml-edge" data-edge-id="([^"]+)"[^>]*/>""").findAll(svg)) {
                val tag = m.value
                val id = m.groupValues[1]
                val d = Regex("""\sd="([^"]+)"""").find(tag)?.groupValues?.get(1)
                out[id] =
                    if (d != null) {
                        SvgGeometry.pathPoints(d)
                    } else {
                        fun attr(n: String) = Regex("""\s$n="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                        listOf(attr("x1") to attr("y1"), attr("x2") to attr("y2"))
                    }
            }
            return out
        }

        fun pathLength(p: List<Pair<Double, Double>>): Double =
            p.zipWithNext().sumOf { (a, b) ->
                hypot(
                    b.first - a.first,
                    b.second - a.second,
                )
            }

        val long60A = "Sends the monthly statement to the customer and archives a copy"
        val long60B = "Confirms receipt of the statement and acknowledges the archive"
        val pairScript =
            """
            c4Model(name = "Pair") {
                val a = softwareSystem(name = "Alpha")
                val b = softwareSystem(name = "Beta")
                relationship(source = a, target = b) { description = "$long60A" }
                relationship(source = b, target = a) { description = "$long60B" }
                systemContextDiagram(name = "Pair") { include(a, b) }
            }
            """.trimIndent()

        test("two opposite 60 character labels do not overlap and cross no foreign edge") {
            val svg = render(pairScript)
            SampleOutput.write(filename = "c4-wave2gb/pair.svg", content = svg)
            val ls = labels(svg)
            ls.size shouldBe 2
            val es = edges(svg)
            es.size shouldBe 2
            val (l1, l2) = ls.values.toList()
            l1.overlaps(l2, eps = 0.0) shouldBe false
            for ((labelId, r) in ls) {
                for ((edgeId, pts) in es) {
                    if (edgeId != labelId) (SvgGeometry.distance(pts, r) > 0.0) shouldBe true
                }
            }
        }

        test("a 120 character label is complete, wrapped at 36 characters and inside the viewBox with a 16 px margin") {
            val text =
                "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo " +
                    "lima mike november oscar papa quebec romeo sierra"
            (text.length > 100) shouldBe true
            val svg =
                render(
                    """
                    c4Model(name = "Long") {
                        val a = softwareSystem(name = "Alpha")
                        val b = softwareSystem(name = "Beta")
                        relationship(source = a, target = b) { description = "$text" }
                        systemContextDiagram(name = "Long") { include(a, b) }
                    }
                    """.trimIndent(),
                )
            SampleOutput.write(filename = "c4-wave2gb/long.svg", content = svg)
            val group = Regex("""<g class="kuml-label kuml-c4-label".*?</g>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!.value
            val lines = Regex("""<tspan[^>]*>([^<]*)</tspan>""").findAll(group).map { it.groupValues[1].trim() }.toList()
            (lines.size >= 4) shouldBe true
            lines.forEach { (it.length <= 36) shouldBe true }
            lines.joinToString(" ") shouldBe text
            val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg)!!.groupValues
            val r = labels(svg).values.single()
            (r.x >= 16.0 - 0.01) shouldBe true
            (r.y >= 16.0 - 0.01) shouldBe true
            (r.right <= vb[1].toDouble() - 16.0 + 0.01) shouldBe true
            (r.bottom <= vb[2].toDouble() - 16.0 + 0.01) shouldBe true
        }

        test("external system and person differ from internal ones in class and fill") {
            val svg =
                render(
                    """
                    c4Model(name = "Ext") {
                        val a = softwareSystem(name = "Own")
                        val b = softwareSystem(name = "Foreign") { external = true }
                        val p = person(name = "Insider")
                        val q = person(name = "Outsider") { external = true }
                        relationship(source = p, target = a) { description = "uses" }
                        relationship(source = q, target = a) { description = "uses" }
                        relationship(source = a, target = b) { description = "calls" }
                        systemContextDiagram(name = "Ext") { include(a, b, p, q) }
                    }
                    """.trimIndent(),
                )
            SampleOutput.write(filename = "c4-wave2gb/external.svg", content = svg)
            val nodes = SvgGeometry.nodes(svg)
            nodes.single { it.id.isNotEmpty() && it.body.contains("Own") }.body shouldContain "class=\"kuml-system\""
            nodes.single { it.body.contains("Foreign") }.body shouldContain "class=\"kuml-system-external\""
            nodes.single { it.body.contains("Outsider") }.body shouldContain "class=\"kuml-person-external\""
            nodes.single { it.body.contains("Insider") }.body shouldNotContain "kuml-person-external"
            val css = { cls: String -> Regex("""\.$cls \{ fill: (#[0-9A-F]{6})""").find(svg)!!.groupValues[1] }
            (css("kuml-system-external") != css("kuml-system")) shouldBe true
            (css("kuml-person-external") != css("kuml-class")) shouldBe true
        }

        test("type tags: no person carries Software System, external ones carry the External prefix") {
            val svg =
                render(
                    """
                    c4Model(name = "Tags") {
                        val a = softwareSystem(name = "Own")
                        val b = softwareSystem(name = "Foreign") { external = true }
                        val q = person(name = "Outsider") { external = true }
                        val p = person(name = "Insider")
                        relationship(source = q, target = a) { description = "uses" }
                        relationship(source = p, target = a) { description = "uses" }
                        relationship(source = a, target = b) { description = "calls" }
                        systemContextDiagram(name = "Tags") { include(a, b, p, q) }
                    }
                    """.trimIndent(),
                )
            val nodes = SvgGeometry.nodes(svg)
            nodes.filter { it.body.contains("Insider") || it.body.contains("Outsider") }.forEach {
                it.body shouldNotContain
                    "[Software System]"
            }
            svg shouldContain "[External Person]"
            svg shouldContain "[External Software System]"
            svg shouldContain "[Software System]"
        }

        test("layout direction = Right is accepted in the container diagram and changes the node order measurably") {
            fun script(layoutBlock: String) =
                """
                c4Model(name = "Dir") {
                    val customer = person(name = "Customer")
                    lateinit var web: dev.kuml.c4.model.C4Container
                    lateinit var api: dev.kuml.c4.model.C4Container
                    val shop = softwareSystem(name = "Shop") {
                        web = container(name = "Web") { technology = "Kotlin" }
                        api = container(name = "Api") { technology = "Kotlin" }
                    }
                    relationship(source = customer, target = web) { description = "browses" }
                    relationship(source = web, target = api) { description = "calls" }
                    containerDiagram(name = "Dir") {
                        system = shop
                        $layoutBlock
                    }
                }
                """.trimIndent()
            val down = SvgGeometry.nodes(render(script("")))
            val right = SvgGeometry.nodes(render(script("layout { direction = Right }")))
            val d = { n: List<SvgNode>, id: String -> n.first { it.body.contains(id) } }
            // top to bottom: the customer sits above the web container; left to right: left of it
            (d(down, "Customer").ty < d(down, "Web").ty) shouldBe true
            (d(right, "Customer").tx < d(right, "Web").tx) shouldBe true
        }

        test("ten labelled edges to one external system: labels apart, close to their line, docking points apart, no detours") {
            val containers = (1..10).joinToString("\n") { "        c$it = container(name = \"Svc$it\") { technology = \"Kotlin\" }" }
            val decls = (1..10).joinToString("\n") { "    lateinit var c$it: dev.kuml.c4.model.C4Container" }
            val rels =
                (1..10).joinToString(
                    "\n",
                ) { "    relationship(source = c$it, target = ext) { description = \"talks to the partner $it\" }" }
            val svg =
                render(
                    """
                    c4Model(name = "Ten") {
                    $decls
                        val shop = softwareSystem(name = "Shop") {
                    $containers
                        }
                        val ext = softwareSystem(name = "Partner") { external = true }
                    $rels
                        containerDiagram(name = "Ten") {
                            system = shop
                            showExternalSystems = true
                        }
                    }
                    """.trimIndent(),
                )
            SampleOutput.write(filename = "c4-wave2gb/ten.svg", content = svg)
            val ls = labels(svg)
            val es = edges(svg)
            ls.size shouldBe 10
            es.size shouldBe 10
            val rects = ls.values.toList()
            for (i in rects.indices) for (j in i + 1 until rects.size) rects[i].overlaps(rects[j], eps = 0.0) shouldBe false
            for ((id, r) in ls) (SvgGeometry.distance(es.getValue(id), r) <= 24.0 + 0.01) shouldBe true
            val tips = es.values.map { it.last() }
            for (i in tips.indices) {
                for (j in i + 1 until tips.size) {
                    (hypot(tips[i].first - tips[j].first, tips[i].second - tips[j].second) >= 16.0 - 0.01) shouldBe true
                }
            }
            for ((id, pts) in es) {
                val air = hypot(pts.last().first - pts.first().first, pts.last().second - pts.first().second)
                (pathLength(pts) <= 1.5 * air + 0.01) shouldBe true
            }
        }

        test("all C4 vault examples render without error") {
            val wanted = listOf("02", "09", "23", "24", "25", "26", "58", "61")
            val found = VaultExampleLoader.loadFromClasspath().filter { ex -> wanted.any { ex.fileName.startsWith("$it ") } }
            found shouldHaveSize wanted.size
            for (ex in found) {
                val r = VaultExampleRenderer.render(script = ex.kumlScript)
                r.error shouldBe null
                r.svg shouldNotBe null
            }
        }
    })
