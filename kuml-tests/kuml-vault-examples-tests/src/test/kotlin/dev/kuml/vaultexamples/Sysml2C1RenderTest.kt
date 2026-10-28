package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Wave C1 (REQ L11 + UC L10) — full pipeline (script, ELK layout, SVG, PNG). The scripts are
 * test resources; assertions are font independent (CSS classes, ids, attributes, geometry
 * containment). The PNGs are written to build/sample-output for visual inspection.
 */
class Sysml2C1RenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2C1RenderTest::class.java
                .getResourceAsStream("/sysml2-c1/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun count(
            s: String,
            needle: String,
        ) = Regex(Regex.escape(needle)).findAll(s).count()

        fun attr(
            tag: String,
            name: String,
        ): Float = Regex("""\b$name="([-0-9.]+)"""").find(tag)!!.groupValues[1].toFloat()

        /** Absolute bounds of `<g id="x" transform="translate(..)">` node groups: id -> [x, y, w, h]. */
        fun frameRects(
            svg: String,
            groupClass: String,
        ): Map<String, FloatArray> =
            Regex("""<g id="([^"]+)" class="$groupClass">\s*<rect ([^>]*)/>""")
                .findAll(svg)
                .associate {
                    val t = it.groupValues[2]
                    it.groupValues[1] to floatArrayOf(attr(t, "x"), attr(t, "y"), attr(t, "width"), attr(t, "height"))
                }

        fun nodeOrigins(svg: String): Map<String, FloatArray> =
            Regex("""<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)"""")
                .findAll(svg)
                .associate { it.groupValues[1] to floatArrayOf(it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }

        fun inside(
            frame: FloatArray,
            p: FloatArray,
        ) = p[0] >= frame[0] && p[1] >= frame[1] && p[0] <= frame[0] + frame[2] && p[1] <= frame[1] + frame[3]

        test("req: all seven relationships, test case, assumption, groups, wrapped title") {
            val r = VaultExampleRenderer.render(script = script("req"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-c1/req.svg", content = svg)
            for (id in listOf(
                "satisfy:Car::TopSpeed",
                "verify:TopSpeedTest::TopSpeed",
                "derive:AbsRequired::StoppingDistanceFromHundredKilometresPerHourOnDryAndWetRoadSurfaces",
            )) {
                svg shouldContain "id=\"$id\""
            }
            for (stereo in listOf("«satisfy»", "«verify»", "«deriveReqt»", "«containment»", "«refine»", "«trace»", "«copy»", "«subject»")) {
                svg shouldContain stereo
            }
            svg shouldContain "«testCase»"
            svg shouldContain "«assumption»"
            // the assumption box has a dashed border, the plain requirements do not
            count(svg, "stroke-dasharray=\"4 3\"") shouldBe 1
            // long title is wrapped over several lines, never truncated
            svg shouldNotContain "…"
            svg shouldContain "priority: High"
            svg shouldContain "status: Approved"
            svg shouldContain "source: Marketing"
            svg shouldContain "subject: Car"
            // three nested groups, outermost first
            val groups = frameRects(svg, "kuml-req-group")
            groups.keys.toSet() shouldBe setOf("group:Performance", "group:Performance/Braking", "group:Performance/Braking/Details")
            val nodes = nodeOrigins(svg)
            val perf = groups.getValue("group:Performance")
            val braking = groups.getValue("group:Performance/Braking")
            val details = groups.getValue("group:Performance/Braking/Details")
            inside(perf, braking) shouldBe true
            inside(braking, details) shouldBe true
            inside(perf, nodes.getValue("TopSpeed")) shouldBe true
            inside(braking, nodes.getValue("StoppingDistanceFromHundredKilometresPerHourOnDryAndWetRoadSurfaces")) shouldBe true
            inside(details, nodes.getValue("StoppingCopy")) shouldBe true
            inside(details, nodes.getValue("AbsRequired")) shouldBe true
            // ungrouped elements stay outside the outermost frame
            inside(perf, nodes.getValue("DryRoadAssumption")) shouldBe false
        }

        test("uc: subject frames contain their use cases, actors stay outside, generalisation arrows") {
            val r = VaultExampleRenderer.render(script = script("uc"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-c1/uc.svg", content = svg)
            val frames = frameRects(svg, "kuml-uc-subject")
            frames.keys shouldBe setOf("ucsubject:Lending", "ucsubject:Administration")
            val nodes = nodeOrigins(svg)
            val lending = frames.getValue("ucsubject:Lending")
            val admin = frames.getValue("ucsubject:Administration")
            for (uc in listOf("Borrow Book", "Authenticate", "Pay Late Fee")) inside(lending, nodes.getValue(uc)) shouldBe true
            for (uc in listOf("Manage Catalogue", "Export Report")) inside(admin, nodes.getValue(uc)) shouldBe true
            for (actor in listOf("User", "Reader", "Librarian")) {
                inside(lending, nodes.getValue(actor)) shouldBe false
                inside(admin, nodes.getValue(actor)) shouldBe false
            }
            // two hollow triangles for the two generalisations (inline arrow polygon with background fill)
            svg shouldContain "id=\"generalization:Reader::User\""
            svg shouldContain "id=\"generalization:Librarian::User\""
            // italic descriptions (max. 4 lines each) inside the two ellipses that carry one
            (count(svg, "kuml-uc-description") in 2..8) shouldBe true
        }

        test("escaping: markup characters in titles, texts, descriptions and group names never reach the SVG raw") {
            val s =
                """
                sysml2Model("Esc") {
                    val r = requirementDef("A<b>&\"x\"", text = "t <script>alert(1)</script> & more", source = "<src>")
                    val u = useCaseDef("U<i>", description = "d <img onerror=x> & \"q\"")
                    val a = actorDef("Act<b>")
                    reqDiagram("R") { group("G<g>&") { include(r) } }
                    ucDiagram("U") { include(u); include(a); association(a, u); subject("S<s>") { include(u) } }
                }
                """.trimIndent()
            val r = VaultExampleRenderer.render(script = s)
            r.error shouldBe null
            // ids are attributes (the builder escapes them once); the assertions below are about text content
            val svg = r.svg!!.replace(Regex(""" id="[^"]*""""), "")
            svg shouldNotContain "<script>"
            svg shouldNotContain "<img"
            svg shouldNotContain "<b>"
            svg shouldNotContain "<g>&"
            svg shouldContain "&lt;script&gt;"
            svg shouldNotContain "&amp;amp;"
            svg shouldNotContain "&amp;lt;"
        }

        test("limits: oversized title, text and description are cut defensively, render still succeeds") {
            val s =
                """
                sysml2Model("Big") {
                    val r = requirementDef("T".repeat(500), text = "word ".repeat(1000), source = "s".repeat(500))
                    val u = useCaseDef("U", description = "long ".repeat(500))
                    reqDiagram("R") { include(r) }
                    ucDiagram("U") { include(u) }
                }
                """.trimIndent()
            val r = VaultExampleRenderer.render(script = s)
            r.error shouldBe null
            (r.svg!!.length < 400_000) shouldBe true
        }

        test("latex: frames, generalisation style and dashed assumption border") {
            val uc = VaultExampleRenderer.render(script = script("uc")).latex!!
            uc shouldContain "kuml-sysml2-edge-generalization"
            uc shouldContain "kuml-sysml2-frame, anchor=north west"
            val req = VaultExampleRenderer.render(script = script("req")).latex!!
            req shouldContain "kuml-class, dashed"
            req shouldContain "testCase"
        }
    })
