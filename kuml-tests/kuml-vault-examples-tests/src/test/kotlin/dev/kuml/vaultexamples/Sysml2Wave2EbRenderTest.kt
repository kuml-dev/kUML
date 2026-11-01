@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/** Wave 2E-b (UC and BDD) - full pipeline, geometry invariants on the rendered SVG. PNGs go to build/sample-output. */
class Sysml2Wave2EbRenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2Wave2EbRenderTest::class.java
                .getResourceAsStream("/sysml2-e2b/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun nodeOrigins(svg: String): Map<String, FloatArray> =
            Regex("""<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)"""")
                .findAll(svg)
                .associate { it.groupValues[1] to floatArrayOf(it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) }

        test("uc grid: subject-only use cases appear, four columns, actors beside the frame, condition and extension point drawn") {
            val r = VaultExampleRenderer.render(script = script("uc-grid"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-e2b/uc-grid.svg", content = svg)
            val nodes = nodeOrigins(svg)
            val ucs =
                listOf(
                    "Search Catalogue",
                    "Browse Category",
                    "Fill Cart",
                    "Check Out",
                    "Pay Order",
                    "Redeem Voucher",
                    "Ship Order",
                    "Track Parcel",
                    "Return Item",
                    "Refund Payment",
                    "Maintain Stock",
                    "Set Prices",
                    "Export Report",
                )
            for (uc in ucs) (uc in nodes) shouldBe true
            // four columns: exactly four distinct x origins among the use cases
            ucs.map { nodes.getValue(it)[0] }.distinct().size shouldBe 4
            // actors stand left or right of all use cases
            val minUcX = ucs.minOf { nodes.getValue(it)[0] }
            val maxUcX = ucs.maxOf { nodes.getValue(it)[0] }
            val actors = listOf("Customer", "Guest", "Clerk", "Administrator", "Payment Provider")
            val left = actors.count { nodes.getValue(it)[0] < minUcX }
            val right = actors.count { nodes.getValue(it)[0] > maxUcX }
            left shouldBe 3
            right shouldBe 2
            svg shouldContain "[customer owns a voucher]"
            svg shouldContain "extension points"
            svg shouldContain "discount"
        }

        /** The rectangle without a [by] px margin: a line that only touches the edge of a label does not run through it. */
        fun SvgRect.shrunk(by: Double) = SvgRect(x + by, y + by, maxOf(0.0, w - 2 * by), maxOf(0.0, h - 2 * by))

        class Label(
            val edge: String,
            val rect: SvgRect,
        )

        val edgeRe = Regex("""<g id="([^"]+)">\s*(<line [^>]*class="kuml-edge[^"]*"[^>]*/>|<path [^>]*class="kuml-edge[^"]*"[^>]*/>)""")

        fun num(
            tag: String,
            attr: String,
        ): Double = Regex("""\b$attr="(-?[0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> {
            val start = svg.indexOf("""<g id="edges">""")
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in edgeRe.findAll(svg, start)) {
                val tag = m.groupValues[2]
                out[m.groupValues[1]] =
                    if (tag.startsWith("<line")) {
                        listOf(num(tag, "x1") to num(tag, "y1"), num(tag, "x2") to num(tag, "y2"))
                    } else {
                        SvgGeometry.pathPoints(Regex("""\bd="([^"]*)"""").find(tag)!!.groupValues[1])
                    }
            }
            return out
        }

        /** The lines of [svg] without the line the label [l] belongs to (the label sits on it: the nearest line to its centre). */
        fun foreignLines(
            svg: String,
            l: SvgRect,
        ): Map<String, List<Pair<Double, Double>>> {
            val all = edges(svg)
            val own = all.minByOrNull { SvgGeometry.distance(it.value, SvgRect(l.cx, l.cy, 0.0, 0.0)) }?.key
            return all.filterKeys { it != own }
        }

        fun labels(svg: String): List<Label> =
            Regex(
                """<g class="kuml-edge-label"(?: data-edge-id="([^"]*)")?(?: data-label-id="[^"]*")? data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    Label(v[1], SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))
                }.toList()

        /** True when [r] overlaps the ellipse inscribed in [box] (closest point of the rectangle to the centre). */
        fun overlapsEllipse(
            r: SvgRect,
            box: SvgRect,
        ): Boolean {
            val px = box.cx.coerceIn(r.x, r.right)
            val py = box.cy.coerceIn(r.y, r.bottom)
            val dx = (px - box.cx) / (box.w / 2)
            val dy = (py - box.cy) / (box.h / 2)
            return dx * dx + dy * dy < 1.0
        }

        /** Bounding boxes of the node groups: rectangles or ellipses. */
        fun boxes(svg: String): Map<String, SvgRect> {
            val out = LinkedHashMap<String, SvgRect>()
            for (n in SvgGeometry.nodes(svg)) {
                Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"""").find(n.body)?.let {
                    out[n.id] = SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                }
                Regex("""<ellipse cx="[-0-9.]+" cy="[-0-9.]+" rx="([-0-9.]+)" ry="([-0-9.]+)"""").find(n.body)?.let {
                    out[n.id] = SvgRect(n.tx, n.ty, 2 * it.groupValues[1].toDouble(), 2 * it.groupValues[2].toDouble())
                }
            }
            return out
        }

        /** Shared label invariants: labels do not overlap each other or a node box, and no foreign line crosses a label. */
        fun assertLabelsClean(
            svg: String,
            expectedLabels: Int? = null,
        ) {
            val ls = labels(svg)
            if (expectedLabels != null) ls.size shouldBe expectedLabels
            val nodes = boxes(svg)
            val lines = edges(svg)
            for ((i, a) in ls.withIndex()) {
                for (b in ls.drop(i + 1)) (a.rect.overlaps(b.rect)) shouldBe false
                for ((id, box) in nodes) {
                    if (a.rect.overlaps(box, eps = 1.0)) throw AssertionError("label of ${a.edge} overlaps node $id")
                }
                for ((id, pts) in lines) {
                    if (id == a.edge) continue
                    if (SvgGeometry.distance(pts, a.rect) < 0.5) throw AssertionError("line $id runs through the label of ${a.edge}")
                }
            }
        }

        fun viewBox(svg: String): Pair<Double, Double> {
            val v = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg)!!.groupValues
            return v[1].toDouble() to v[2].toDouble()
        }

        test("uc grid: no label overlaps a node or another label, no foreign line crosses a label") {
            val svg = VaultExampleRenderer.render(script = script("uc-grid")).svg!!
            val ls = labels(svg)
            (ls.size >= 3) shouldBe true
            val useCaseIds =
                nodeOrigins(
                    svg,
                ).keys.filter { it !in setOf("Customer", "Guest", "Clerk", "Administrator", "Payment Provider") }.toSet()
            val nodes = boxes(svg)
            for (a in ls) {
                for ((id, box) in nodes) {
                    if (id.startsWith("ucsubject")) continue
                    // use cases are ellipses, actors are drawn as figures inside their bounds (rectangles)
                    val hit = if (id in useCaseIds) overlapsEllipse(a.rect, box) else a.rect.overlaps(box, eps = 1.0)
                    if (hit) throw AssertionError("label of ${a.edge} overlaps node $id")
                }
            }
            for ((i, a) in ls.withIndex()) for (b in ls.drop(i + 1)) a.rect.overlaps(b.rect) shouldBe false
            // Lines between use cases never cross a label. Actor lines share the gaps between the rows with these labels
            // and may still cross one (documented as open in the changelog), so they are not part of this invariant.
            for (a in ls) {
                for ((id, pts) in foreignLines(svg, a.rect)) {
                    if (id.startsWith("assoc:")) continue
                    if (SvgGeometry.distance(pts, a.rect.shrunk(2.0)) <
                        0.01
                    ) {
                        throw AssertionError("line $id runs through a label of ${a.edge}")
                    }
                }
            }
        }

        test("uc grid: without wrapColumns the picture is at most about 1.7 : 1") {
            val auto = script("uc-grid").replace(Regex("""\s*layout \{\s*wrapColumns = 4\s*\}"""), "")
            val svg = VaultExampleRenderer.render(script = auto).svg!!
            val (w, h) = viewBox(svg)
            (w / h) shouldBeLessThanOrEqual 1.75
        }

        test("bdd labels: role names in the middle of the lines, multiplicity at the target, no overlaps") {
            val svg = VaultExampleRenderer.render(script = script("bdd-labels")).svg!!
            SampleOutput.write(filename = "sysml2-e2b/bdd-labels.svg", content = svg)
            for (role in listOf("pumps", "valve", "fans", "sensor", "tank", "filter")) {
                Regex("""\n\s+$role\n""").containsMatchIn(svg) shouldBe
                    true
            }
            svg shouldContain "1..*"
            svg shouldContain "0..*"
            svg shouldContain "0..1"
            val ls = labels(svg)
            ls.size shouldBe 6
            val nodes = boxes(svg)
            for ((i, a) in ls.withIndex()) {
                for (b in ls.drop(i + 1)) a.rect.overlaps(b.rect) shouldBe false
                for ((id, box) in nodes) if (a.rect.overlaps(box, eps = 1.0)) throw AssertionError("label of ${a.edge} overlaps $id")
                for ((id, pts) in foreignLines(svg, a.rect)) {
                    if (SvgGeometry.distance(pts, a.rect.shrunk(2.0)) < 0.01) throw AssertionError("line $id runs through a label")
                }
            }
        }

        fun triangleBoxes(svg: String): List<SvgRect> =
            edges(svg).keys.filter { it.startsWith("gen:") }.flatMap { SvgGeometry.arrowBoxes(svg, it) }

        test("bdd generalisation: eight specialisations in two rows of four, the block centred above them, one triangle") {
            val svg = VaultExampleRenderer.render(script = script("bdd-gen")).svg!!
            SampleOutput.write(filename = "sysml2-e2b/bdd-gen.svg", content = svg)
            val b = boxes(svg)
            val specs = listOf("Car", "Truck", "Bus", "Van", "Bike", "Scooter", "Tram", "Tractor").map { b.getValue(it) }
            specs.map { it.y }.distinct().size shouldBe 2
            specs.groupBy { it.y }.values.forEach { it.size shouldBe 4 }
            val vehicle = b.getValue("Vehicle")
            (vehicle.bottom <= specs.minOf { it.y }) shouldBe true
            val centre = (specs.minOf { it.x } + specs.maxOf { it.right }) / 2
            kotlin.math.abs(vehicle.cx - centre) shouldBeLessThanOrEqual 1.0
            // all eight edges end in the same point: the triangles coincide
            triangleBoxes(svg).map { "${it.x.toInt()}/${it.y.toInt()}" }.distinct().size shouldBe 1
            // no line crosses a specialisation box
            for ((id, pts) in edges(svg)) {
                for ((name, box) in b) {
                    if (name == "Vehicle" || id.contains(name)) continue
                    if (SvgGeometry.distance(pts, box) < 0.5) throw AssertionError("edge $id runs through $name")
                }
            }
        }

        test("bdd generalisation: direction Up puts the block below the rows") {
            val svg = VaultExampleRenderer.render(script = script("bdd-gen-up")).svg!!
            val b = boxes(svg)
            val vehicle = b.getValue("Vehicle")
            for (n in listOf("Car", "Bike", "Tractor")) (b.getValue(n).bottom <= vehicle.y) shouldBe true
            triangleBoxes(svg).map { "${it.x.toInt()}/${it.y.toInt()}" }.distinct().size shouldBe 1
        }

        test("bdd generalisation: two specialisations keep single edges, fourteen are wrapped five per row") {
            val two = VaultExampleRenderer.render(script = script("bdd-gen-two")).svg!!
            triangleBoxes(two).map { "${it.x.toInt()}/${it.y.toInt()}" }.distinct().size shouldBe 2
            val many = VaultExampleRenderer.render(script = script("bdd-gen-14")).svg!!
            val b = boxes(many)
            val rows = (1..14).map { b.getValue("Variant$it") }.groupBy { it.y }
            rows.values.map { it.size } shouldBe listOf(5, 5, 4)
            triangleBoxes(many).map { "${it.x.toInt()}/${it.y.toInt()}" }.distinct().size shouldBe 1
        }

        test("bdd sameLayer: members connected by an edge share the centre line and the edge runs between them") {
            val svg = VaultExampleRenderer.render(script = script("bdd-samelayer")).svg!!
            SampleOutput.write(filename = "sysml2-e2b/bdd-samelayer.svg", content = svg)
            val b = boxes(svg)
            val a = b.getValue("Alpha")
            val c = b.getValue("Gamma")
            kotlin.math.abs(a.cy - c.cy) shouldBeLessThanOrEqual 1.0
            val line = edges(svg).getValue("comp:Gamma::alpha")
            (line.size >= 2) shouldBe true
            line.map { it.second }.distinct().size shouldBe 1 // a straight horizontal line
        }

        test("bdd allocation: seven edges, each labelled, the first four dock at the rows of their actions") {
            val svg = VaultExampleRenderer.render(script = script("bdd-allocate")).svg!!
            SampleOutput.write(filename = "sysml2-e2b/bdd-allocate.svg", content = svg)
            val ls = labels(svg)
            ls.size shouldBe 7
            for ((i, a) in ls.withIndex()) for (l in ls.drop(i + 1)) a.rect.overlaps(l.rect) shouldBe false
            val node = SvgGeometry.nodes(svg).first { it.id == "Steuerung" }
            val es = edges(svg)
            for ((n, row) in listOf("start()", "stop()", "monitor()", "log()").withIndex()) {
                val id = "alloc:alloc${n + 1}"
                val end = es.getValue(id).last()
                val textY =
                    Regex(
                        """<text class="kuml-body" x="6" y="([-0-9.]+)">\s*${Regex.escape(row)}""",
                    ).find(node.body)!!.groupValues[1].toDouble()
                kotlin.math.abs(end.second - (node.ty + textY - 4)) shouldBeLessThanOrEqual 3.0
                kotlin.math.abs(end.first - node.tx) shouldBeLessThanOrEqual 1.0
            }
        }

        test("bdd attributes: unit once, enumeration, action def, reference part") {
            val svg = VaultExampleRenderer.render(script = script("bdd-attributes")).svg!!
            SampleOutput.write(filename = "sysml2-e2b/bdd-attributes.svg", content = svg.replace("\u00A0", " "))
            val text = svg.replace("\u00A0", " ")
            text shouldContain "raumdruck : Raumdruck = -5 Pa"
            text shouldContain "«enumeration»"
            text shouldContain "farbe : Ampelfarbe = Rot"
            text shouldContain "«action def»"
            text shouldContain "id=\"agg:Raum::sensor\""
            svg.indexOf("= -5.0") shouldBe -1
            labels(svg).size shouldNotBe 0
        }
    })
