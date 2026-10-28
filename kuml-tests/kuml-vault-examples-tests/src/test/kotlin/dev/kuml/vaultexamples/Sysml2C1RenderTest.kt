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

        /** All edge polylines (id -> points), read from `<line>` / `<path d>` / `<polyline>` elements of edge groups. */
        fun edgePolylines(svg: String): Map<String, List<Pair<Float, Float>>> {
            val out = LinkedHashMap<String, List<Pair<Float, Float>>>()
            val num = Regex("""-?[0-9]+(?:\.[0-9]+)?""")
            for (g in Regex("""<g id="([^"]+)">\s*(<line [^>]*/>|<path [^>]*/>|<polyline [^>]*/>)""").findAll(svg)) {
                val tag = g.groupValues[2]
                val pts =
                    when {
                        tag.startsWith("<line") ->
                            listOf(attr(tag, "x1") to attr(tag, "y1"), attr(tag, "x2") to attr(tag, "y2"))
                        else -> {
                            val raw = Regex("""\b(?:d|points)="([^"]+)"""").find(tag)!!.groupValues[1]
                            num
                                .findAll(raw)
                                .map { it.value.toFloat() }
                                .toList()
                                .chunked(2)
                                .filter { it.size == 2 }
                                .map { it[0] to it[1] }
                        }
                    }
                out[g.groupValues[1]] = pts
            }
            return out
        }

        test("req: group tabs are painted after every edge, with an opaque fill") {
            val svg = VaultExampleRenderer.render(script = script("req")).svg!!
            val lastEdge = svg.lastIndexOf("id=\"satisfy:")
            (svg.indexOf("id=\"tab:group:Performance\"") > lastEdge) shouldBe true
            svg shouldContain "fill-opacity=\"1\""
        }

        test("req: no edge label sits further than the leash from its own edge line") {
            val svg = VaultExampleRenderer.render(script = script("req")).svg!!
            val lines = edgePolylines(svg)
            val satisfy = lines.entries.first { it.key.startsWith("satisfy:") }
            // the «satisfy» label text lies within 16 px (+ glyph height) of its own polyline
            val g = svg.substring(svg.indexOf("<g id=\"${satisfy.key}\">"))
            val label = Regex("""<text class="kuml-stereotype" x="([-0-9.]+)" y="([-0-9.]+)"""").find(g.substring(0, g.indexOf("</g>")))!!
            val lx = label.groupValues[1].toFloat()
            val ly = label.groupValues[2].toFloat()
            var best = Float.MAX_VALUE
            val pts = satisfy.value
            for (i in 0 until pts.size - 1) {
                for (k in 0..20) {
                    val t = k / 20f
                    val x = pts[i].first + (pts[i + 1].first - pts[i].first) * t
                    val y = pts[i].second + (pts[i + 1].second - pts[i].second) * t
                    best = minOf(best, kotlin.math.hypot(x - lx, y - ly))
                }
            }
            (best <= 16f + 22f) shouldBe true
        }

        test("uc: subject titles are painted after the edges") {
            val svg = VaultExampleRenderer.render(script = script("uc")).svg!!
            (svg.indexOf("id=\"title:ucsubject:Lending\"") > svg.lastIndexOf("id=\"generalization:")) shouldBe true
        }

        test("uc: subject frame group still carries only the frame rectangle") {
            val svg = VaultExampleRenderer.render(script = script("uc")).svg!!
            val g = svg.substring(svg.indexOf("<g id=\"ucsubject:Lending\""))
            g.substring(0, g.indexOf("</g>")) shouldNotContain "<text"
        }

        test("uc: subject frames contain their use cases contain their use cases, actors stay outside, generalisation arrows") {
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
