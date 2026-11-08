package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 42: use case diagram with a subject frame, five actors and eleven notes (the Personalschleuse example).
 * Everything is read from the geometry of the rendered SVG (`d`, `x1..y2`, `ellipse`, `transform`), never from text
 * metrics, so the invariants hold on every machine.
 */
class UcPersonalschleuseInvariantTest :
    FunSpec({
        timeout = 90_000L

        data class P(
            val x: Float,
            val y: Float,
        )

        data class R(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
        ) {
            fun gapTo(o: R): Float {
                val dx = max(0f, max(o.x - (x + w), x - (o.x + o.w)))
                val dy = max(0f, max(o.y - (y + h), y - (o.y + o.h)))
                return if (dx == 0f && dy == 0f) -1f else max(dx, dy)
            }
        }

        data class Ell(
            val cx: Float,
            val cy: Float,
            val rx: Float,
            val ry: Float,
        ) {
            fun residual(p: P): Float {
                val nx = (p.x - cx) / rx
                val ny = (p.y - cy) / ry
                return abs(nx * nx + ny * ny - 1f) * min(rx, ry)
            }

            /** True if the rectangle has a point strictly inside the ellipse. */
            fun overlaps(r: R): Boolean {
                val px = cx.coerceIn(r.x, r.x + r.w)
                val py = cy.coerceIn(r.y, r.y + r.h)
                val nx = (px - cx) / rx
                val ny = (py - cy) / ry
                return nx * nx + ny * ny < 1f - 1e-3f
            }
        }

        class Arc(
            val r: Float,
            val from: P,
            val to: P,
        )

        class Wire(
            val id: String,
            val pts: List<P>,
            val arcs: List<Arc>,
            val straight: List<Pair<P, P>>,
        )

        val svg by lazy {
            val code =
                UcPersonalschleuseInvariantTest::class.java
                    .getResourceAsStream("/sysml2-c4/usecase-personalschleuse.kuml.kts")!!
                    .bufferedReader()
                    .readText()
            val r = VaultExampleRenderer.render(script = code)
            r.error shouldBe null
            r.svg!!
        }

        val num = """-?\d+(?:\.\d+)?"""

        fun ellipses(): Map<String, Ell> =
            Regex(
                """<g id="([^"]+)" transform="translate\(($num),($num)\)">\s*<ellipse cx="($num)" cy="($num)" rx="($num)" ry="($num)" class="kuml-usecase"""",
            ).findAll(svg)
                .associate { m ->
                    val v = m.groupValues
                    v[1] to
                        Ell(
                            cx = v[2].toFloat() + v[4].toFloat(),
                            cy = v[3].toFloat() + v[5].toFloat(),
                            rx = v[6].toFloat(),
                            ry = v[7].toFloat(),
                        )
                }

        fun notes(): Map<String, R> =
            Regex(
                """<g id="([^"]+)" transform="translate\(($num),($num)\)">\s*<path d="M 0 0 L ($num) 0 L ($num) 14 L \5 ($num) L 0 \6 Z" class="kuml-comment""",
            ).findAll(svg)
                .associate { m ->
                    val v = m.groupValues
                    v[1] to R(x = v[2].toFloat(), y = v[3].toFloat(), w = v[4].toFloat(), h = v[6].toFloat())
                }

        fun actors(): Map<String, R> =
            Regex("""<g id="([^"]+)" transform="translate\(($num),($num)\)">\s*<circle cx="($num)" cy="($num)" r="8" class="kuml-actor"""")
                .findAll(svg)
                .associate { m ->
                    val v = m.groupValues
                    // the stick figure is 24 px wide and reaches from the head top (cy - 8) to y = 78
                    v[1] to R(x = v[2].toFloat() + v[4].toFloat() - 12f, y = v[3].toFloat() + v[5].toFloat() - 8f, w = 24f, h = 74f)
                }

        fun parse(
            id: String,
            d: String,
        ): Wire {
            val tok = Regex("""([MLA])\s*((?:$num[ ,]*)+)""").findAll(d).toList()
            val pts = ArrayList<P>()
            val arcs = ArrayList<Arc>()
            val straight = ArrayList<Pair<P, P>>()
            var cur: P? = null
            for (t in tok) {
                val n = Regex(num).findAll(t.groupValues[2]).map { it.value.toFloat() }.toList()
                when (t.groupValues[1]) {
                    "M" -> cur = P(x = n[0], y = n[1]).also { pts += it }
                    "L" -> {
                        val q = P(x = n[0], y = n[1])
                        straight += cur!! to q
                        pts += q
                        cur = q
                    }
                    "A" -> {
                        val q = P(x = n[5], y = n[6])
                        arcs += Arc(r = n[0], from = cur!!, to = q)
                        pts += q
                        cur = q
                    }
                }
            }
            return Wire(id = id, pts = pts, arcs = arcs, straight = straight)
        }

        fun edges(): List<Wire> =
            Regex("""<g id="((?:assoc|extend|include)[^"]*)">\s*<path d="([^"]+)" class="kuml-edge"""")
                .findAll(svg)
                .map { parse(id = it.groupValues[1], d = it.groupValues[2]) }
                .toList()

        class Anchor(
            val note: String,
            val target: String,
            val end: P,
        )

        fun anchors(): List<Anchor> =
            Regex("""<g id="note-link:([^:]+)::([^"]+)">\s*<(?:path|line)[^>]*>\s*<circle cx="($num)" cy="($num)"""")
                .findAll(svg)
                .map {
                    Anchor(
                        note = it.groupValues[1],
                        target = it.groupValues[2],
                        end = P(x = it.groupValues[3].toFloat(), y = it.groupValues[4].toFloat()),
                    )
                }.toList()

        test("fixture renders the expected number of elements") {
            ellipses().size shouldBe 13
            notes().size shouldBe 11
            actors().size shouldBe 5
            edges().size shouldBe 18
            anchors().size shouldBe 15
        }

        test("invariant a - every note anchor at a use case ends on the ellipse outline") {
            val ell = ellipses()
            val bad =
                anchors()
                    .filter {
                        it.target in ell
                    }.filter { ell.getValue(it.target).residual(it.end) > 1.5f }
                    .map { "${it.note}->${it.target}" }
            bad.shouldBeEmpty()
        }

        test("invariant b - notes overlap no ellipse, no actor and no other note, and keep 8 px from each other") {
            val ell = ellipses().values.toList()
            val actors = actors().values.toList()
            val notes = notes()
            val problems = ArrayList<String>()
            for ((id, r) in notes) {
                if (ell.any { it.overlaps(r) }) problems += "$id overlaps a use case"
                if (actors.any { it.gapTo(r) < 0f }) problems += "$id overlaps an actor"
            }
            val list = notes.entries.toList()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val g = list[i].value.gapTo(list[j].value)
                    if (g < 8f) problems += "${list[i].key}/${list[j].key} gap $g"
                }
            }
            problems.shouldBeEmpty()
        }

        test("invariant c - no two edges share a collinear piece and parallel neighbours keep 8 px") {
            val all =
                edges().flatMap { w -> w.straight.map { w.id to it } }.filter { (_, s) ->
                    abs(s.first.x - s.second.x) +
                        abs(s.first.y - s.second.y) >
                        1f
                }
            val problems = ArrayList<String>()
            for (i in all.indices) {
                for (j in i + 1 until all.size) {
                    val (ia, a) = all[i]
                    val (ib, b) = all[j]
                    if (ia == ib) continue
                    val ah = abs(a.first.y - a.second.y) < 0.01f
                    val bh = abs(b.first.y - b.second.y) < 0.01f
                    val av = abs(a.first.x - a.second.x) < 0.01f
                    val bv = abs(b.first.x - b.second.x) < 0.01f
                    if (ah && bh) {
                        val o =
                            min(max(a.first.x, a.second.x), max(b.first.x, b.second.x)) -
                                max(min(a.first.x, a.second.x), min(b.first.x, b.second.x))
                        if (o > 1f && abs(a.first.y - b.first.y) < 7.5f) problems += "H $ia/$ib d=${abs(a.first.y - b.first.y)}"
                    }
                    if (av && bv) {
                        val o =
                            min(max(a.first.y, a.second.y), max(b.first.y, b.second.y)) -
                                max(min(a.first.y, a.second.y), min(b.first.y, b.second.y))
                        if (o > 1f && abs(a.first.x - b.first.x) < 7.5f) problems += "V $ia/$ib d=${abs(a.first.x - b.first.x)}"
                    }
                }
            }
            problems.shouldBeEmpty()
        }

        test("invariant d - no bump: every arc is a real corner and no two arcs follow each other directly") {
            val problems = ArrayList<String>()
            for (w in edges()) {
                for (a in w.arcs) {
                    if (abs(a.from.x - a.to.x) < 0.5f || abs(a.from.y - a.to.y) < 0.5f) problems += "${w.id}: half circle at ${a.from}"
                }
                // S jog: an arc whose end is the start of the next arc
                for (a in w.arcs) {
                    if (w.arcs.any { b -> b !== a && abs(b.from.x - a.to.x) < 0.5f && abs(b.from.y - a.to.y) < 0.5f }) {
                        problems +=
                            "${w.id}: S jog at ${a.to}"
                    }
                }
            }
            problems.shouldBeEmpty()
        }

        test("invariant e - the extend line has at most two bends") {
            val ext = edges().single { it.id.startsWith("extend:") }
            ext.arcs.size shouldBe 2
        }

        test("invariant f - association ends at a use case lie on the ellipse") {
            val ell = ellipses()
            val bad = ArrayList<String>()
            for (w in edges()) {
                // ids are "assoc:<A>::<B>" or "extend:<A>::<B>"
                val (s, t) = w.id.substringAfter(':').split("::")
                if (s in ell && ell.getValue(s).residual(w.pts.first()) > 1.5f) bad += "${w.id} start"
                if (t in ell && ell.getValue(t).residual(w.pts.last()) > 1.5f) bad += "${w.id} end"
            }
            bad.shouldBeEmpty()
        }

        test("invariant g - the length of single-use-case anchors is reported (asserted only with the note reserve)") {
            val ell = ellipses()
            val notes = notes()
            val single = anchors().groupBy { it.note }.filter { it.value.size == 1 && it.value.first().target in ell }
            val lengths =
                single.map { (id, a) ->
                    val r = notes.getValue(id)
                    val end = a.first().end
                    id to max(max(r.x - end.x, end.x - (r.x + r.w)), max(r.y - end.y, end.y - (r.y + r.h)))
                }
            println("UC anchors, distance note to line end (px): $lengths")
            (lengths.size >= 5) shouldBe true
        }
    })
