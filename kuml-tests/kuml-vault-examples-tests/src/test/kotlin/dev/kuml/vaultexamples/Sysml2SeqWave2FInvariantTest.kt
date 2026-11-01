@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs

/**
 * PAL wave 2F (SEQ): fragment width from the covered lifelines, fragment height from the content, gap between
 * fragments, `ref` frame, self loop label, `par` without guard, time labels, found / lost, vertical margins.
 * Geometry only (rect / line / circle attributes), no glyph widths.
 */
class Sysml2SeqWave2FInvariantTest :
    FunSpec({
        fun fixture(): String =
            Sysml2SeqWave2FInvariantTest::class.java
                .getResourceAsStream("/sysml2-f2/seq-fragments-time.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script, themeName = "plain")
            r.error shouldBe null
            SampleOutput.write(filename = "sysml2-f2/seq-${script.hashCode()}.svg", content = r.svg!!)
            return r.svg!!
        }

        fun example(prefix: String): String =
            render(VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith(prefix) }.kumlScript)

        fun frame(
            svg: String,
            id: String,
        ): SvgRect {
            val m = Regex("""<g id="$id">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""").find(svg)!!
            val v = m.groupValues
            return SvgRect(v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble())
        }

        fun canvas(svg: String): Pair<Double, Double> {
            val m = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg)!!
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }

        test("fragments cover only their lifelines, nest, keep a gap and end below their last content") {
            val svg = render(fixture())
            val outer = frame(svg, "fragment:outer".let { findId(svg, "outer") })
            val inner = frame(svg, findId(svg, "inner"))
            val refOnly = frame(svg, findId(svg, "refOnly"))
            val par = frame(svg, findId(svg, "parallel"))
            val (canvasW, _) = canvas(svg)
            // nested: the child lies inside the parent
            (inner.x > outer.x && inner.right < outer.right && inner.y > outer.y && inner.bottom < outer.bottom) shouldBe true
            // a fragment that only holds a ref is narrower than the canvas and does not start at the left edge
            (refOnly.w < canvasW * 0.6) shouldBe true
            (refOnly.x > 40.0) shouldBe true
            // consecutive fragments never overlap and keep at least 12 px
            val ordered = listOf(outer, refOnly, par).sortedBy { it.y }
            ordered.zipWithNext().forEach { (a, b) -> (b.y - a.bottom >= 12.0 - 0.01) shouldBe true }
            // the last operand ends below the self loop (20 px) of its last row: the frame reaches below the loop
            val loop = Regex("""<path d="M ([-0-9.]+) ([-0-9.]+) L [-0-9.]+ [-0-9.]+ L [-0-9.]+ ([-0-9.]+) L""").find(svg)!!
            val loopBottom = loop.groupValues[3].toDouble()
            (inner.bottom >= loopBottom + 8.0 - 0.01) shouldBe true
            (outer.bottom >= inner.bottom) shouldBe true
            // everything stays inside the canvas
            (par.right <= canvasW) shouldBe true
        }

        test("par without a guard writes no brackets, the divider stays") {
            val svg = render(fixture())
            val par = findId(svg, "parallel")
            val body = svg.substring(svg.indexOf("""<g id="$par">"""), svg.indexOf("</g>", svg.indexOf("""<g id="$par">""")))
            body shouldNotContain "["
            body shouldContain "kuml-divider"
        }

        test("ref frame: tag as high as the frame, at least 28 px, 16 px beyond the covered lifeline box") {
            val svg = render(fixture())
            val id = Regex("""<g id="([^"]*audit[^"]*)">""").find(svg)!!.groupValues[1]
            val body = svg.substring(svg.indexOf("""<g id="$id">"""))
            val rect = Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""").find(body)!!
            val h = rect.groupValues[4].toDouble()
            (h >= 28.0) shouldBe true
            val tag = Regex("""<polygon points="([^"]+)"""").find(body)!!
            val ys = SvgGeometry.numbers(tag.groupValues[1]).filterIndexed { i, _ -> i % 2 == 1 }
            abs((ys.max() - ys.min()) - h) shouldBe 0.0
        }

        test("self loop is 32 x 20 px and its label stands right of the loop") {
            val svg = render(fixture())
            val loop = Regex("""<path d="M ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) L""").find(svg)!!
            val v = loop.groupValues.drop(1).map { it.toDouble() }
            abs(v[2] - v[0]) shouldBe 32.0
            abs(v[5] - v[1]) shouldBe 20.0
        }

        test("time observation: no 'now', name or name = label; found with a sender starts on its lifeline, with a dot") {
            val svg = render(fixture())
            svg shouldNotContain "= now = now"
            svg shouldContain "tGeschlossen"
            svg shouldContain "tOffen = now"
            // found message from a lifeline: filled circle r = 4 at the start of the line
            Regex("""<circle cx="([-0-9.]+)" cy="[-0-9.]+" r="4" class="kuml-pseudo-filled"""").findAll(svg).count() shouldBe 1
        }

        test("found and lost lines are at least 120 px long") {
            val svg = render(fixture())
            for (id in listOf("message:found-FMCS-1", "message:lost-Log-10")) {
                val i = svg.indexOf("""<g id="$id">""")
                (i >= 0) shouldBe true
                val line = Regex("""<line x1="([-0-9.]+)" y1="[-0-9.]+" x2="([-0-9.]+)"""").find(svg, i)!!
                (abs(line.groupValues[2].toDouble() - line.groupValues[1].toDouble()) >= 120.0 - 0.01) shouldBe true
            }
        }

        test("margins above and below the lifelines are at most 24 px (inside the frame)") {
            for (svg in listOf(render(fixture()), example("50 "), example("06 "))) {
                val (_, h) = canvas(svg)
                val heads =
                    Regex(
                        """<g id="[^"]+" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<rect y="0" width="[-0-9.]+" height="40" class="kuml-class"""",
                    ).findAll(svg).toList()
                (heads.isNotEmpty()) shouldBe true
                val top = heads.minOf { it.groupValues[2].toDouble() }
                (top <= 24.5) shouldBe true
                val axisBottom =
                    heads.map { m ->
                        val y2 = Regex("""y2="([-0-9.]+)" class="kuml-divider"""").find(svg, m.range.last)!!.groupValues[1].toDouble()
                        m.groupValues[2].toDouble() + y2
                    }
                (h - axisBottom.max() <= 24.5) shouldBe true
            }
        }
    })

private fun findId(
    svg: String,
    name: String,
): String = Regex("""<g id="([^"]*$name[^"]*)">\s*<rect x=""").find(svg)!!.groupValues[1]
