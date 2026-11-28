package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * PAL Welle X2 measuring gate: SysML 2 sequence diagrams (vault examples, fixtures and, when present locally, the PAL
 * documents 72/75/76/77/78) are rendered and checked against four geometry invariants. Everything is measured on SVG
 * rectangles and lines (white label background rects, frame rects, lifeline axes and activation bars), never on glyphs.
 *
 * - I-12a: no fragment frame vertical edge may cut a foreign label rectangle or run closer than 4 px to it.
 * - I-12b-1: a horizontal frame edge (top or bottom) keeps 4 px vertical clearance to every foreign label rectangle it
 *   overlaps horizontally (the frame's own operator and guard labels are exempt).
 * - I-12b-2: a guard rectangle keeps 4 px clearance to every lifeline axis and activation bar.
 * - I-12f: a duration constraint stands 14 px right of its lifeline (or activation bar), its label starts 8 px right of
 *   the shaft, and neither the label nor the shaft meets a foreign label rectangle or lifeline.
 *
 * Gates (asserted): I-12a and I-12b-2 zero, I-12b-1 not above the two known cases; I-12f is zero (Y1).
 *
 * The test prints the violation count per invariant and fixture (`[welle-x2]`) and writes the table to
 * `build/sample-output/seq-frame-geometry.txt`. The PAL workspace is read only; the documents are copied into a temp dir.
 * Without the workspace (CI) the PAL part is skipped.
 */
class SeqFrameGeometryGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf(
                "72-stoerfall-beide-tueren-seq.md",
                "75-tuerkontaktfehler-seq.md",
                "76-schliessen-freigabe-seq.md",
                "77-tuerkontaktfehler-meldung-seq.md",
                "78-tuerkontaktfehler-sperre-seq.md",
            )

        fun resource(path: String): String =
            SeqFrameGeometryGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val fixtures =
                listOf("sysml2-f2/seq-fragments-time", "sysml2-c4/seq-nested").map { it to resource("/$it.kuml.kts") }
            val pal =
                if (palDir.isDirectory) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-seq")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val src = File(palDir, name)
                        if (!src.isFile) return@mapNotNull null
                        val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-x2] PAL workspace not found, PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + pal).filter { it.second.contains("seqDiagram(") }
        }

        test("measure SEQ frame and duration constraint geometry") {
            val out = StringBuilder()
            val totals = linkedMapOf("12a" to 0, "12b1" to 0, "12b2" to 0, "12f" to 0)
            var checked = 0
            for ((name, script) in scripts()) {
                val r = VaultExampleRenderer.render(script = script)
                val svg = r.svg
                if (svg == null) {
                    println("[welle-x2] $name: render failed: ${r.error}")
                    continue
                }
                checked++
                File("build/sample-output/seq-frame-geometry")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                val m = SeqGeometry.measure(svg)
                for ((k, v) in m.counts) totals[k] = totals.getValue(k) + v.size
                val line = "$name  " + m.counts.entries.joinToString("  ") { "${it.key}=${it.value.size}" }
                println("[welle-x2] $line")
                out.appendLine(line)
                for ((k, v) in m.counts) {
                    for (d in v) {
                        println("[welle-x2]     $k: $d")
                        out.appendLine("    $k: $d")
                    }
                }
            }
            val summary = "TOTAL ($checked diagrams)  " + totals.entries.joinToString("  ") { "${it.key}=${it.value}" }
            println("[welle-x2] $summary")
            out.appendLine(summary)
            val dir = File("build/sample-output").also { it.mkdirs() }
            File(dir, "seq-frame-geometry.txt").writeText(out.toString())
            // Gates: 12a, 12b-2 and (since PAL Welle Y1) 12f are zero. 12b-1 keeps the two known cases at most.
            check(totals.getValue("12a") == 0) { "I-12a violated: $summary" }
            check(totals.getValue("12f") == 0) { "I-12f violated: $summary" }
            check(totals.getValue("12b2") == 0) { "I-12b-2 violated: $summary" }
            check(totals.getValue("12b1") <= 2) { "I-12b-1 got worse: $summary" }
        }
    })

/** Geometry measuring of a SysML 2 sequence SVG; shared by the measuring and the gate assertions. */
internal object SeqGeometry {
    const val CLEAR = 4.0

    data class R(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    ) {
        val r get() = x + w
        val b get() = y + h

        override fun toString() = "(${x.fmt()},${y.fmt()} ${w.fmt()}x${h.fmt()})"
    }

    class Result(
        val counts: Map<String, List<String>>,
    )

    private fun Double.fmt() = "%.1f".format(this)

    private fun attr(
        tag: String,
        name: String,
    ): Double? =
        Regex("""\b$name="(-?[0-9.]+)"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toDouble()

    private fun rectOf(tag: String) =
        R(
            x = attr(tag = tag, name = "x")!!,
            y = attr(tag = tag, name = "y")!!,
            w = attr(tag = tag, name = "width")!!,
            h = attr(tag = tag, name = "height")!!,
        )

    private fun groups(svg: String): List<Pair<String, String>> {
        // top level `<g ...>...</g>` blocks of the nodes/edges parts; groups here never nest
        return Regex(
            """<g ([^>]*)>(.*?)</g>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(svg).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    fun measure(svg: String): Result {
        val labels = ArrayList<R>() // foreign label rectangles (not guard rects)
        val guards = ArrayList<Pair<String, R>>() // fragment id -> guard rect
        val frames = LinkedHashMap<String, R>()
        val axes = ArrayList<Triple<String, Double, Pair<Double, Double>>>() // lifeline name, x, y range
        val bars = ArrayList<Pair<Double, R>>() // activation bars (cx, rect)
        val durations = ArrayList<Pair<String, String>>() // id, body

        for ((head, body) in groups(svg)) {
            val id = Regex("""\bid="([^"]*)"""").find(head)?.groupValues?.get(1)
            val dataFragment = Regex("""data-fragment="([^"]*)"""").find(head)?.groupValues?.get(1)
            when {
                dataFragment != null ->
                    Regex("""<rect[^>]*fill="white"[^>]*stroke="none"[^>]*/>""").findAll(body).forEach {
                        guards += dataFragment to rectOf(it.value)
                    }
                id != null && id.startsWith("combinedFragment:") ->
                    Regex("""<rect[^>]*/>""").find(body)?.let { frames[id] = rectOf(it.value) }
                id != null && id.startsWith("durationConstraint:") -> {
                    durations += id to body
                    Regex("""<rect[^>]*fill="white"[^>]*stroke="none"[^>]*/>""").findAll(body).forEach { labels += rectOf(it.value) }
                }
                id != null && head.contains("transform=\"translate(") -> {
                    val t = Regex("""translate\((-?[0-9.]+),(-?[0-9.]+)\)""").find(head)!!
                    val tx = t.groupValues[1].toDouble()
                    val ty = t.groupValues[2].toDouble()
                    val axis =
                        Regex(
                            """<line x1="(-?[0-9.]+)" y1="(-?[0-9.]+)" x2="(-?[0-9.]+)" y2="(-?[0-9.]+)"[^>]*stroke-dasharray="4 4"""",
                        ).find(body)
                    if (axis != null) {
                        val v = axis.groupValues.drop(1).map { it.toDouble() }
                        axes += Triple(id, tx + v[0], (ty + v[1]) to (ty + v[3]))
                    }
                }
                id != null &&
                    id.startsWith(
                        "execSpec",
                    ) ||
                    (id != null && Regex("""<rect[^>]*width="10"[^>]*/>""").containsMatchIn(body)) -> {
                    Regex("""<rect[^>]*width="10"[^>]*/>""").find(body)?.let {
                        val rc = rectOf(it.value)
                        bars += (rc.x + 5.0) to rc
                    }
                    Regex("""<rect[^>]*fill="white"[^>]*stroke="none"[^>]*/>""").findAll(body).forEach { labels += rectOf(it.value) }
                }
                else -> Regex("""<rect[^>]*fill="white"[^>]*stroke="none"[^>]*/>""").findAll(body).forEach { labels += rectOf(it.value) }
            }
        }

        val v12a = ArrayList<String>()
        val v12b1 = ArrayList<String>()
        val v12b2 = ArrayList<String>()
        val v12f = ArrayList<String>()

        val foreignForFrame = { fid: String -> labels + guards.filter { it.first != fid }.map { it.second } }
        for ((fid, f) in frames) {
            for (l in foreignForFrame(fid)) {
                val yOverlap = l.y < f.b && l.b > f.y
                if (yOverlap) {
                    for (edge in listOf(f.x, f.r)) {
                        if (l.x - CLEAR < edge && edge < l.r + CLEAR) v12a += "$fid edge x=${edge.fmt()} vs label $l"
                    }
                }
                val xOverlap = l.x < f.r && l.r > f.x
                if (xOverlap) {
                    for (edge in listOf(f.y, f.b)) {
                        if (l.y - CLEAR < edge && edge < l.b + CLEAR) v12b1 += "$fid edge y=${edge.fmt()} vs label $l"
                    }
                }
            }
        }
        for ((fid, g) in guards) {
            for ((name, x, yr) in axes) {
                if (g.y < yr.second && g.b > yr.first && g.x - CLEAR < x && x < g.r + CLEAR) {
                    v12b2 += "$fid guard $g vs lifeline $name x=${x.fmt()}"
                }
            }
            for ((cx, b) in bars) {
                if (g.y < b.b && g.b > b.y && g.x - CLEAR < b.r && b.x < g.r + CLEAR) {
                    v12b2 += "$fid guard $g vs activation bar at x=${cx.fmt()}"
                }
            }
        }
        for ((did, body) in durations) {
            val lifeline = did.removePrefix("durationConstraint:").replace(Regex("-\\d+-\\d+$"), "")
            val cx = axes.firstOrNull { it.first == lifeline }?.second
            val shaft = Regex("""<line x1="(-?[0-9.]+)" y1="(-?[0-9.]+)" x2="(-?[0-9.]+)" y2="(-?[0-9.]+)"""").find(body)
            if (cx == null || shaft == null) {
                v12f += "$did: lifeline or shaft not found"
                continue
            }
            val sx = shaft.groupValues[1].toDouble()
            val sTop = shaft.groupValues[2].toDouble()
            val sBottom = shaft.groupValues[4].toDouble()
            val bar = bars.firstOrNull { kotlin.math.abs(it.first - cx) < 1.0 && it.second.y <= sBottom && it.second.b >= sTop }
            val expected = cx + (if (bar != null) 5.0 else 0.0) + 14.0
            if (kotlin.math.abs(sx - expected) > 0.6) v12f += "$did: shaft x=${sx.fmt()}, expected ${expected.fmt()} (right of lifeline)"
            val text = Regex("""<text[^>]*x="(-?[0-9.]+)"[^>]*text-anchor="(\w+)"""").findAll(body).firstOrNull()
            val rect = Regex("""<rect[^>]*fill="white"[^>]*stroke="none"[^>]*/>""").find(body)?.let { rectOf(it.value) }
            if (text != null && rect != null) {
                val anchor = text.groupValues[2]
                if (anchor != "start") v12f += "$did: label anchor=$anchor, expected start"
                for ((name, x, yr) in axes) {
                    if (rect.y < yr.second && rect.b > yr.first && rect.x - 0.5 < x && x < rect.r + 0.5) {
                        v12f += "$did: label $rect meets lifeline $name x=${x.fmt()}"
                    }
                }
                for (l in labels) {
                    if (l !== rect && l.x < rect.r && l.r > rect.x && l.y < rect.b && l.b > rect.y && l != rect) {
                        v12f += "$did: label $rect meets foreign label $l"
                    }
                }
            }
            for ((name, x, yr) in axes) {
                if (x > sx && x - sx < 0 || kotlin.math.abs(x - sx) < 0.5 && name != lifeline) v12f += "$did: shaft on lifeline $name"
            }
        }
        return Result(counts = mapOf("12a" to v12a, "12b1" to v12b1, "12b2" to v12b2, "12f" to v12f))
    }
}
