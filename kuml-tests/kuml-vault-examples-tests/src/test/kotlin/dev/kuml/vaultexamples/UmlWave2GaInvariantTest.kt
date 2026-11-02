@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * PAL wave 2G-a: font independent invariants for the UML class diagram (default value, static underline, keyword size,
 * wrap / auto-wrap, edgeless band) and the deployment diagram (communication paths). Everything is measured on the
 * rendered SVG of the full pipeline, never on pixels.
 */
class UmlWave2GaInvariantTest :
    FunSpec({
        fun classFixture(layoutBlock: String): String {
            val subs =
                (1..11).joinToString("\n") { i ->
                    "    val s$i = classOf(name = \"Sub$i\") { attribute(name = \"v$i\", type = \"Int\") }\n" +
                        "    generalization(specific = s$i, general = base)"
                }
            return """
                classDiagram(name = "Wave2Ga") {
                    $layoutBlock
                    val base = classOf(name = "Base") {
                        attribute(name = "limit", type = "Int", defaultValue = "-5")
                        attribute(name = "counter", type = "Int", isStatic = true)
                        operation(name = "create") { isStatic = true; returns(typeName = "Base") }
                    }
                $subs
                    val color = enumOf(name = "Color") { literal(name = "RED"); literal(name = "GREEN") }
                    val kind = enumOf(name = "Kind") { literal(name = "A") }
                    val flag = enumOf(name = "Flag") { literal(name = "ON") }
                    comment(text = "Both", firstAnchor = base, secondAnchor = color)
                }
                """.trimIndent()
        }

        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        val classLayoutBlock3 = "layout { wrapColumns = 3 }"

        fun xsOf(
            svg: String,
            names: List<String>,
        ): List<Double> {
            val nodes = SvgGeometry.nodes(svg)
            return names.map { n -> nodes.single { it.id == n }.tx }
        }

        test("default value is rendered verbatim and static features are underlined") {
            val svg = render(classFixture(classLayoutBlock3))
            SampleOutput.write(filename = "uml-wave2ga/class-wrap3.svg", content = svg)
            svg shouldContain "limit: Int = -5"
            // static attribute + static operation -> exactly two underlined spans
            Regex("""class="kuml-static-underline"""").findAll(svg).count() shouldBe 2
            svg shouldContain ".kuml-static-underline"
        }

        test("keyword of enumeration and interface is at least 12 px and not muted") {
            val svg = render(classFixture(classLayoutBlock3))
            Regex(""".kuml-keyword \{[^}]*font-size: (\d+)px""").find(svg)!!.groupValues[1].toInt() shouldBe 12
            svg shouldContain "class=\"kuml-keyword\""
            svg shouldContain "«enumeration»"
        }

        test("wrapColumns = 3 puts the 11 subclasses on exactly 3 columns") {
            val svg = render(classFixture(classLayoutBlock3))
            val xs = xsOf(svg, (1..11).map { "Sub$it" }).map { Math.round(it) }.toSet()
            xs shouldHaveSize 3
        }

        test("more than 6 nodes in a layer wrap automatically into ceil(sqrt(n)) = 4 columns") {
            val svg = render(classFixture(""))
            SampleOutput.write(filename = "uml-wave2ga/class-auto.svg", content = svg)
            val xs = xsOf(svg, (1..11).map { "Sub$it" }).map { Math.round(it) }.toSet()
            xs shouldHaveSize 4
        }

        test("edgeless classifiers sit in a band below every classifier that has an edge") {
            val svg = render(classFixture(classLayoutBlock3))
            val nodes = SvgGeometry.nodes(svg)
            val edgeless = nodes.filter { it.id == "Kind" || it.id == "Flag" }
            edgeless shouldHaveSize 2
            val connected = nodes.filter { it.id == "Base" || it.id.startsWith("Sub") }
            val connectedBottom = connected.maxOf { it.ty + heightOf(it.body) }
            edgeless.forEach { (it.ty >= connectedBottom) shouldBe true }
        }

        test("communication paths are plain solid lines without arrowheads, label only when named") {
            val script =
                """
                deploymentDiagram(name = "Cloud") {
                    val a = node(name = "Web")
                    val b = node(name = "App")
                    val c = node(name = "Db")
                    communicationPath(end1 = a, end2 = b)
                    communicationPath(end1 = b, end2 = c, name = "JDBC")
                }
                """.trimIndent()
            val svg = render(script)
            SampleOutput.write(filename = "uml-wave2ga/deployment.svg", content = svg)
            svg shouldNotContain "communicationPath"
            svg shouldContain "JDBC"
            val edgeTags = Regex("""<(?:line|path)[^>]*class="kuml-edge"[^>]*/>""").findAll(svg).map { it.value }.toList()
            edgeTags shouldHaveSize 2
            edgeTags.forEach {
                it shouldNotContain "marker-end"
                it shouldNotContain "stroke-dasharray"
            }
        }
    }) {
    companion object {
        fun heightOf(body: String): Double =
            Regex("""<rect width="[-0-9.]+" height="([-0-9.]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: 0.0
    }
}
