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
            svg shouldContain "A vehicle owns its engine."
        }
    })
