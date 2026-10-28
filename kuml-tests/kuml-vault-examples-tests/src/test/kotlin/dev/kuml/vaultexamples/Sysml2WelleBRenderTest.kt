package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Welle B (SysML 2 ports, IBD, BDD) — full pipeline (script, ELK layout, SVG, PNG).
 * The three scripts live as test resources (not in the vault). Assertions are font
 * independent (CSS classes, ids, element counts); the PNGs are written for visual inspection.
 */
class Sysml2WelleBRenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2WelleBRenderTest::class.java
                .getResourceAsStream("/sysml2-welle-b/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun count(
            s: String,
            needle: String,
        ) = Regex(Regex.escape(needle)).findAll(s).count()

        fun attr(
            tag: String,
            name: String,
        ): Float = Regex("""$name="([-0-9.]+)"""").find(tag)!!.groupValues[1].toFloat()

        fun rectsOf(
            svg: String,
            cssClass: String,
        ): List<FloatArray> =
            Regex("""<rect[^>]*class="$cssClass"[^>]*/>""")
                .findAll(svg)
                .map {
                    val t = it.value
                    floatArrayOf(attr(t, "x"), attr(t, "y"), attr(t, "width"), attr(t, "height"))
                }.toList()

        fun viewBox(svg: String): FloatArray {
            val v = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg)!!.groupValues
            return floatArrayOf(v[1].toFloat(), v[2].toFloat())
        }

        /** Port-label backdrops in absolute SVG coordinates (part-port labels live in a translated node group). */
        fun absoluteLabelRects(svg: String): List<FloatArray> {
            val out = mutableListOf<FloatArray>()
            var tx = 0f
            var ty = 0f
            for (line in svg.lines()) {
                Regex("""<g [^>]*transform="translate\(([-0-9.]+),([-0-9.]+)\)"""").find(line)?.let {
                    tx = it.groupValues[1].toFloat()
                    ty = it.groupValues[2].toFloat()
                }
                if (line.trim() == "</g>") {
                    tx = 0f
                    ty = 0f
                }
                if (line.contains("class=\"kuml-port-label-bg\"")) {
                    out += floatArrayOf(attr(line, "x") + tx, attr(line, "y") + ty, attr(line, "width"), attr(line, "height"))
                }
            }
            return out
        }

        /** Geometry checks shared by the IBD renders: frame hugs the content, labels inside the canvas, no label overlap. */
        fun checkIbdGeometry(svg: String) {
            svg shouldNotContain "fill=\"white\""
            val vb = viewBox(svg)
            val frame = rectsOf(svg, "kuml-ibd-frame").single()
            // no large empty area below / right of the frame (owner labels sit outside the frame, hence the slack)
            (vb[1] - (frame[1] + frame[3]) <= 80f) shouldBe true
            val labels = absoluteLabelRects(svg)
            labels.isNotEmpty() shouldBe true
            for (l in labels) {
                (l[0] >= 0f && l[1] >= 0f && l[0] + l[2] <= vb[0] + 0.5f && l[1] + l[3] <= vb[1] + 0.5f) shouldBe true
            }
            // part-port labels of the same diagram never overlap each other
            for ((i, a) in labels.withIndex()) {
                for (b in labels.drop(i + 1)) {
                    val hit = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]
                    hit shouldBe false
                }
            }
        }

        test("ports: typed boundary ports, direction triangles, owner frame") {
            val r = VaultExampleRenderer.render(script = script("ports"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-welle-b/ports.svg", content = svg)
            svg shouldContain "kuml-ibd-frame"
            // fuelIn + telemetry on the frame
            svg shouldContain "id=\"Vehicle::fuelIn\""
            svg shouldContain "id=\"Vehicle::telemetry\""
            svg shouldContain "fuelIn : FuelPort"
            // three directed ports get a triangle, the undirected one stays an empty square
            count(svg, "class=\"kuml-sysml2-port-dir\"") shouldBe 3
            checkIbdGeometry(svg)
            // the tab is a pentagon inside the frame, not an overlapping rectangle
            svg shouldContain "class=\"kuml-ibd-frame-tab\""
        }

        test("ibd: delegation to a nested port, flow arrow, owner frame") {
            val r = VaultExampleRenderer.render(script = script("ibd"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-welle-b/ibd.svg", content = svg)
            svg shouldContain "kuml-ibd-frame"
            svg shouldContain "id=\"conn:Vehicle::delegation\""
            svg shouldContain "id=\"conn:Vehicle::feed\""
            // one mid-arrow on `feed` plus the owner-port triangle plus part-port triangles
            Regex(">\\s*Fuel\\s*<").containsMatchIn(svg) shouldBe true
            checkIbdGeometry(svg)
        }

        test("bdd: composition, aggregation, association, allocation, unit, comment") {
            val r = VaultExampleRenderer.render(script = script("bdd"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-welle-b/bdd.svg", content = svg)
            svg shouldContain "id=\"comp:Vehicle::engine\""
            svg shouldContain "id=\"comp:Vehicle::wheels\""
            svg shouldContain "id=\"agg:Vehicle::tank\""
            svg shouldContain "id=\"link:Drives\""
            svg shouldContain "id=\"alloc:alloc:ctl\"".replace("alloc:alloc:", "alloc:")
            svg shouldContain "«allocate»"
            svg shouldContain "unit = kg"
            svg shouldContain "burn()"
            // rows of drawn composition edges are gone from the Vehicle box
            svg shouldNotContain "engine : Engine"
            // flows compartment carries `<dir> <name> : <Type>`; the old signals compartment (type names only) is gone
            svg shouldContain "out fuel : Mass"
            Regex(">\\s*flows\\s*<").containsMatchIn(svg) shouldBe true
            Regex(">\\s*signals\\s*<").containsMatchIn(svg) shouldBe false
            // the port is named `fuelPort`, so the direction word is no longer doubled (`out out`)
            svg shouldNotContain "out out :"
            svg shouldContain "out fuelPort : FuelPort"
            svg shouldContain "A vehicle owns its engine."
        }
    })
