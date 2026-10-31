package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL 2B-c: in SysML 2 IBD and BDD diagrams no connector label overlaps a note, and the rectangle the renderer draws a
 * label at is the rectangle that was planned (the drawn `data-label-rect` equals the position of the text lines).
 * Works on rectangles read from the SVG structure, not on font metrics.
 */
class Sysml2LabelNoteInvariantTest :
    FunSpec({
        val examples = VaultExampleLoader.loadFromClasspath().filter { Regex("^(03|27|54|63) ").containsMatchIn(it.fileName) }

        class R(
            val x: Double,
            val y: Double,
            val w: Double,
            val h: Double,
        ) {
            fun hits(o: R) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
        }

        val noteRe =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<path d="M 0 0 L [-0-9.]+ 0 L ([-0-9.]+) [-0-9.]+ L [-0-9.]+ ([-0-9.]+)""",
            )
        val labelRe = Regex("""<g class="kuml-edge-label" data-label-rect="([-0-9. ]+)">(.*?)</g>""", RegexOption.DOT_MATCHES_ALL)
        val textRe = Regex("""<text[^>]* x="([-0-9.]+)" y="([-0-9.]+)"""")

        fun notes(svg: String) =
            noteRe
                .findAll(svg)
                .map {
                    val g = it.groupValues
                    g[1] to R(g[2].toDouble(), g[3].toDouble(), g[4].toDouble(), g[5].toDouble())
                }.toList()

        fun labels(svg: String) =
            labelRe
                .findAll(svg)
                .map {
                    val v =
                        it.groupValues[1]
                            .trim()
                            .split(' ')
                            .map(String::toDouble)
                    R(v[0], v[1], v[2], v[3]) to it.groupValues[2]
                }.toList()

        test("the examples are present") { (examples.size >= 4) shouldBe true }

        for (example in examples) {
            test("example ${example.fileName.substringBefore(' ')}: no label overlaps a note, drawn text sits in the planned rectangle") {
                val svg = VaultExampleRenderer.render(script = example.kumlScript, themeName = "plain").svg!!
                SampleOutput.write(filename = "sysml2-label-note/${example.sanitizedName}.svg", content = svg)
                val ns = notes(svg)
                val ls = labels(svg)
                val bad = ArrayList<String>()
                for ((rect, body) in ls) {
                    for ((id, n) in ns) if (rect.hits(n)) bad += "label at ${rect.x},${rect.y} overlaps note $id"
                    for (m in textRe.findAll(body)) {
                        val tx = m.groupValues[1].toDouble()
                        val ty = m.groupValues[2].toDouble()
                        if (tx < rect.x ||
                            tx > rect.x + rect.w ||
                            ty < rect.y ||
                            ty > rect.y + rect.h + 1.0
                        ) {
                            bad += "text at $tx,$ty outside its planned rectangle"
                        }
                    }
                }
                bad.joinToString("\n") shouldBe ""
                if (example.fileName.startsWith("54 ")) {
                    ns.size shouldBe 3
                    (ls.isNotEmpty()) shouldBe true
                }
            }
        }
    })
