@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * PAL wave J2: transition label placement of the real "Doc 51" state machine (the vault example, copied as the classpath
 * fixture `sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts`), measured on the rendered SVG of the production pipeline:
 *
 *  * I1 no two transition labels come closer than 2 px,
 *  * I2 no label comes closer than 2 px to a foreign line (edges and note anchors),
 *  * I2b a label does not straddle the frame of a composite state (it lies inside it or 2 px outside it),
 *  * I3 a label stays near its own line (rectangle distance at most 14 px; a one-line label beside a horizontal segment
 *       has its centre at most 24 px from it).
 *
 * Only geometry, no fonts and no pixel or byte comparison.
 */
class Doc51StmLabelInvariantsTest :
    FunSpec({
        timeout = 300_000L

        class Label(
            val id: String,
            val rect: SvgRect,
        )

        val edgeRe =
            Regex("""<g id="([^"]+)">\s*(<line [^>]*class="kuml-(?:edge|note-anchor)"[^>]*/>|<path [^>]*class="kuml-edge"[^>]*/>)""")

        fun num(
            tag: String,
            attr: String,
        ): Double = Regex("""\b$attr="(-?[0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()

        /** Own lines by id: the transitions, and the note anchors (id `note-link:...`). */
        fun lines(svg: String): Map<String, List<Pair<Double, Double>>> {
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in edgeRe.findAll(svg, svg.indexOf("""<g id="edges">""").coerceAtLeast(0))) {
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

        fun labels(svg: String): List<Label> =
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    Label(v[1], SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))
                }.toList()

        fun frames(svg: String): List<SvgRect> =
            Regex(
                """<g id="[^"]+" class="kuml-composite-state">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)
                .map {
                    SvgRect(
                        it.groupValues[1].toDouble(),
                        it.groupValues[2].toDouble(),
                        it.groupValues[3].toDouble(),
                        it.groupValues[4].toDouble(),
                    )
                }.toList()

        /** I1, I2, I2b and I3 problems of one rendered diagram as (label id, text) pairs (empty = all hold). */
        fun problems(
            name: String,
            svg: String,
        ): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val fr = frames(svg)
            val ls = labels(svg)
            val ln = lines(svg)
            for (i in ls.indices) {
                for (j in i + 1 until ls.size) {
                    val gap = ls[i].rect.gapTo(ls[j].rect)
                    if (gap < 2.0) {
                        out += ls[i].id to "$name: labels ${ls[i].id} and ${ls[j].id} are ${"%.1f".format(gap)} px apart (I1)"
                        out += ls[j].id to "$name: labels ${ls[i].id} and ${ls[j].id} are ${"%.1f".format(gap)} px apart (I1)"
                    }
                }
            }
            for (l in ls) {
                for ((eid, pts) in ln) {
                    if (eid == l.id) continue
                    val d = SvgGeometry.distance(pts, l.rect)
                    if (d < 2.0 - 0.01) out += l.id to "$name: label ${l.id} is ${"%.1f".format(d)} px from the line $eid (I2)"
                }
                for (f in fr) {
                    val inside = f.contains(l.rect, margin = 2.0)
                    val outside = !l.rect.overlaps(SvgRect(f.x - 2.0, f.y - 2.0, f.w + 4.0, f.h + 4.0))
                    if (!inside && !outside) out += l.id to "$name: label ${l.id} straddles a composite frame (I2b)"
                }
                val own = ln[l.id] ?: continue
                val d = SvgGeometry.distance(own, l.rect)
                if (d > 14.0 + 0.01) out += l.id to "$name: label ${l.id} is ${"%.1f".format(d)} px from its line (I3)"
                if (l.rect.h <= 18.5) {
                    // One-line label: the centre is at most 24 px from a horizontal segment it sits beside.
                    for (k in 0 until own.size - 1) {
                        val (a, b) = own[k] to own[k + 1]
                        val horizontal = abs(a.second - b.second) < 1.0 && abs(a.first - b.first) > 1.0
                        val beside = l.rect.cx in minOf(a.first, b.first)..maxOf(a.first, b.first)
                        if (horizontal && beside && abs(l.rect.cy - a.second) <= l.rect.h / 2 + 14.0 + 0.01) {
                            if (abs(l.rect.cy - a.second) > 24.0 + 0.01) {
                                out +=
                                    l.id to
                                    "$name: label ${l.id} centre is ${"%.1f".format(abs(l.rect.cy - a.second))} px from its line (I3)"
                            }
                        }
                    }
                }
            }
            return out
        }

        fun script(resource: String): String =
            Doc51StmLabelInvariantsTest::class.java
                .getResourceAsStream(resource)!!
                .bufferedReader()
                .readText()

        val doc51 = "/sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts"

        test("the fixture renders with every transition label") {
            val r = VaultExampleRenderer.render(script = script(doc51))
            r.error shouldBe null
            SampleOutput.write(filename = "sysml2-stm-j2/51-rueckluftklappe-stm.svg", content = r.svg!!)
            labels(r.svg!!).size shouldBe 13
        }

        // Open points (PAL Welle J2): these two labels sit in a corridor between parallel lines (or a line and a composite frame) that
        // is narrower than the narrowest wrap of the label, so the planner reports them as KUML-LAYOUT-W013 and keeps a compromise.
        // Closing them needs label-aware spreading of the routes (J1 follow-up). The test fixes exactly this set: a label that
        // gets a free position makes the second test fail, so the entry has to be removed here.
        val knownOpen =
            setOf(
                "transition:Tür Mixing offen::Beide Türen offen",
                "transition:Tür Korridor offen::Wartezeit",
            )

        test("I1 to I3: every label except the known open ones touches no other label and no foreign line and stays at its line") {
            val svg = VaultExampleRenderer.render(script = script(doc51)).svg!!
            problems("doc51", svg).filter { it.first !in knownOpen }.map { it.second }.joinToString("\n") shouldBe ""
        }

        test("the known open labels are exactly the ones that still violate (remove an entry once it is fixed)") {
            val svg = VaultExampleRenderer.render(script = script(doc51)).svg!!
            problems("doc51", svg)
                .map { it.first }
                .toSet()
                .filter { it in knownOpen }
                .toSet() shouldBe knownOpen
        }

        test("the labels that collided before the repair (T16 and T19) are apart and clear") {
            val svg = VaultExampleRenderer.render(script = script(doc51)).svg!!
            val bad = problems("doc51", svg).map { it.first }.toSet()
            ("transition:Tür Korridor offen::Beide Türen offen" in bad) shouldBe false
            ("transition:Wartezeit::Tür Korridor offen" in bad) shouldBe false
        }
    })
