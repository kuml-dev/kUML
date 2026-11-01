package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave 2C-b2: on the IBD / ACT examples 27, 49 and 54 no label (edge label or port label, halo not counted)
 * overlaps another label, a note or a part box. Works on the rectangles the renderer stamps on the label groups
 * (`data-label-rect`), not on font metrics, so it holds on every machine.
 */
class Sysml2B2LabelInvariantTest :
    FunSpec({
        val examples = VaultExampleLoader.loadFromClasspath().filter { Regex("^(27|49|54) ").containsMatchIn(it.fileName) }

        class R(
            val x: Double,
            val y: Double,
            val w: Double,
            val h: Double,
        ) {
            fun hits(o: R) = x + 0.5 < o.x + o.w && o.x + 0.5 < x + w && y + 0.5 < o.y + o.h && o.y + 0.5 < y + h
        }

        val groupOpen = Regex("""<g [^>]*>""")
        val translate = Regex("""transform="translate\(([-0-9.]+),([-0-9.]+)\)"""")
        val labelRect = Regex("""data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""")
        val noteRe =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<path d="M 0 0 L [-0-9.]+ 0 L ([-0-9.]+) [-0-9.]+ L [-0-9.]+ ([-0-9.]+)""",
            )
        val boxRe =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<rect width="([-0-9.]+)" height="([-0-9.]+)" [^>]*class="kuml-class"""",
            )

        /** Labels in absolute coordinates; a port label group sits inside a translated node group, an edge label does not. */
        fun labels(svg: String): List<Pair<String, R>> {
            val out = ArrayList<Pair<String, R>>()
            var tx = 0.0
            var ty = 0.0
            var depth = 0
            var nodeDepth = -1
            for (line in svg.lines()) {
                val t = line.trim()
                if (t.startsWith("<g ")) {
                    translate.find(t)?.let {
                        if (nodeDepth < 0) {
                            tx = it.groupValues[1].toDouble()
                            ty = it.groupValues[2].toDouble()
                            nodeDepth = depth
                        }
                    }
                    labelRect.find(t)?.let {
                        val v = it.groupValues.drop(1).map(String::toDouble)
                        out += (if (t.contains("port-label-group")) "port" else "edge") to R(v[0] + tx, v[1] + ty, v[2], v[3])
                    }
                    if (!t.endsWith("/>")) depth++
                } else if (t == "</g>") {
                    depth--
                    if (depth == nodeDepth) {
                        tx = 0.0
                        ty = 0.0
                        nodeDepth = -1
                    }
                }
            }
            return out
        }

        test("the examples are present") { examples.size shouldBe 3 }

        for (example in examples) {
            test("example ${example.fileName.substringBefore(' ')}: no label overlaps another label, a note or a part box") {
                val svg = VaultExampleRenderer.render(script = example.kumlScript, themeName = "plain").svg!!
                SampleOutput.write(filename = "sysml2-b2/${example.sanitizedName}.svg", content = svg)
                val ls = labels(svg)
                val notes =
                    noteRe
                        .findAll(svg)
                        .map {
                            val g = it.groupValues
                            g[1] to R(g[2].toDouble(), g[3].toDouble(), g[4].toDouble(), g[5].toDouble())
                        }.toList()
                val boxes =
                    boxRe
                        .findAll(svg)
                        .map {
                            val g = it.groupValues
                            g[1] to R(g[2].toDouble(), g[3].toDouble(), g[4].toDouble(), g[5].toDouble())
                        }.toList()
                val bad = ArrayList<String>()
                for ((i, a) in ls.withIndex()) {
                    for (j in i + 1 until ls.size) {
                        if (a.second.hits(ls[j].second)) {
                            bad +=
                                "${a.first} label $i overlaps ${ls[j].first} label $j"
                        }
                    }
                    for ((id, n) in notes) if (a.second.hits(n)) bad += "label $i overlaps note $id"
                    if (a.first == "edge") for ((id, b) in boxes) if (a.second.hits(b)) bad += "edge label $i overlaps part box $id"
                }
                if (example.fileName.startsWith("54 ")) {
                    // B: no note anchor runs through a note or a part box (2 px tolerance at its own start and end).
                    val anchors =
                        Regex("""<path d="([^"]+)" class="kuml-note-anchor"/>""")
                            .findAll(svg)
                            .map { m ->
                                Regex("""[-0-9.]+""")
                                    .findAll(m.groupValues[1])
                                    .map { it.value.toDouble() }
                                    .toList()
                                    .chunked(2)
                            }.toList() +
                            Regex("""<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-note-anchor"/>""")
                                .findAll(svg)
                                .map { m ->
                                    listOf(
                                        listOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()),
                                        listOf(m.groupValues[3].toDouble(), m.groupValues[4].toDouble()),
                                    )
                                }.toList()
                    (anchors.size >= 3) shouldBe true
                    for (a in anchors) {
                        (a.size - 2 <= 2) shouldBe true
                        for (i in 0 until a.size - 1) {
                            val (x0, y0) = a[i]
                            val (x1, y1) = a[i + 1]
                            val steps = maxOf(1, Math.hypot(x1 - x0, y1 - y0).toInt())
                            for (k in 0..steps) {
                                val px = x0 + (x1 - x0) * k / steps
                                val py = y0 + (y1 - y0) * k / steps
                                for ((id, o) in notes + boxes) {
                                    if (px > o.x + 2 &&
                                        px < o.x + o.w - 2 &&
                                        py > o.y + 2 &&
                                        py < o.y + o.h - 2
                                    ) {
                                        bad += "anchor runs through $id"
                                    }
                                }
                            }
                        }
                    }
                }
                if (!example.fileName.startsWith("49 ")) (ls.size >= 3) shouldBe true
                bad.joinToString("\n") shouldBe ""
            }
        }
    })
