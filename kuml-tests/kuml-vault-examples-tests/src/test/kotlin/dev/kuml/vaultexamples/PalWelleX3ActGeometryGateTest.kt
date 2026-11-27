@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * PAL Welle X3 measuring gate: SysML 2 activity diagrams (vault examples, fixtures, three include orders of the combined
 * 62 / 67 fixture and, when present locally, the PAL documents 60-63 and 65-67) are rendered and checked against five
 * geometry invariants, all measured on SVG rectangles and polylines (see [ActGeometryClearance]).
 *
 * A1 node gap below 8 px, A2 flow through a foreign node, A3 object flow label far from its line or on a node, A4 SVG
 * size / PNG height against the viewBox, A5 free note too close to its surroundings.
 *
 * Gates (asserted): the values measured on the baseline are the upper bounds; they are tightened where a step of this
 * wave fixed something. The PAL workspace is read only (documents are copied to a temp dir); without it (CI) the PAL part
 * is skipped. The include-order permutations must give the same violation counts (order invariance).
 */
class PalWelleX3ActGeometryGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf(
                "60-personendurchgang-act.md",
                "61-einfrierlogik-act.md",
                "62-stoerungsbehandlung-act.md",
                "63-stoerungsbehandlung-druck-act.md",
                "65-stoerungsbehandlung-steuerung-act.md",
                "66-personendurchgang-mixing-act.md",
                "67-stoerungsbehandlung-tuerkontakt-act.md",
            )
        val nodeNames =
            listOf(
                "start",
                "vorlauf",
                "wahl",
                "msgBeide",
                "alarm",
                "rlk",
                "msgTuer",
                "sperren",
                "flucht",
                "sammler",
                "pruefen",
                "ende",
                "flussende",
            )

        fun resource(path: String): String =
            PalWelleX3ActGeometryGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun combined(order: List<String>): String =
            resource("/sysml2-act-x3/62-67-kombiniert.kuml.kts")
                .replace("//INCLUDES//", order.joinToString("\n") { "        include(node = $it)" })

        val permutations =
            linkedMapOf(
                "x3/original" to nodeNames,
                "x3/reversed" to nodeNames.reversed(),
                "x3/f1-first" to
                    listOf(
                        "start",
                        "vorlauf",
                        "wahl",
                        "msgTuer",
                        "sperren",
                        "flucht",
                        "msgBeide",
                        "alarm",
                        "rlk",
                        "sammler",
                        "pruefen",
                        "ende",
                        "flussende",
                    ),
            )

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val fixtures =
                listOf("sysml2-f2/act-all-2f", "sysml2-f2/act-lanes-16", "sysml2-c2/act").map { it to resource("/$it.kuml.kts") }
            val x3 = permutations.map { (k, v) -> k to combined(v) }
            val pal =
                if (palDir.isDirectory) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-act")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val src = File(palDir, name)
                        if (!src.isFile) return@mapNotNull null
                        val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-x3] PAL workspace not found, PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + x3 + pal).filter { it.second.contains("actDiagram(") }
        }

        test("measure ACT geometry") {
            val out = StringBuilder()
            val totals = linkedMapOf("A1" to 0, "A2" to 0, "A3" to 0, "A4" to 0, "A5" to 0)
            val perX3 = LinkedHashMap<String, String>()
            var freeNotes = 0
            var checked = 0
            for ((name, script) in scripts()) {
                val r = VaultExampleRenderer.render(script = script)
                val svg = r.svg
                if (svg == null) {
                    println("[welle-x3] $name: render failed: ${r.error}")
                    continue
                }
                checked++
                File("build/sample-output/act-geometry")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                val m = ActGeometryClearance.measure(svg)
                val svgDev = ActGeometryClearance.svgSizeDeviation(svg)
                val pngDev = ActGeometryClearance.pngHeightDeviation(svg)
                val a4 = ArrayList<String>()
                if (svgDev > 0.5) a4 += "SVG width/height deviate ${"%.2f".format(svgDev)} px from the viewBox"
                if (kotlin.math.abs(pngDev) > 1) a4 += "PNG height deviates $pngDev px"
                freeNotes += m.freeNotes
                // W012 guard: no inventory diagram carries one of the options the lane pass reports (so no new W012 anywhere).
                check(!Regex("""\b(wrapColumns|maxWidth|aspectRatio)\s*=""").containsMatchIn(script)) { "$name carries a W012 option" }
                val counts = m.counts + ("A4" to a4)
                for ((k, v) in counts) totals[k] = totals.getValue(k) + v.size
                val line =
                    "$name  " + counts.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}=${it.value.size}" } +
                        "  png-dev=$pngDev  free-notes=${m.freeNotes}"
                println("[welle-x3] $line")
                out.appendLine(line)
                for ((k, v) in counts) {
                    for (d in v) {
                        println("[welle-x3]     $k: $d")
                        out.appendLine("    $k: $d")
                    }
                }
                if (name.startsWith("x3/")) {
                    perX3[name] =
                        counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.size}" }
                }
            }
            val summary =
                "TOTAL ($checked diagrams)  " + totals.entries.joinToString("  ") { "${it.key}=${it.value}" } + "  free-notes=$freeNotes"
            println("[welle-x3] $summary")
            out.appendLine(summary)
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/act-geometry.txt").writeText(out.toString())

            check(perX3.values.toSet().size <= 1) { "include order changes the violations: $perX3" }
            // Baseline (before this wave): A2 = 15 (vault 48: 1, act-all-2f: 11, x3 fixture: 3 = o2 through "Betroffene Tür
            // sperren" in all three include orders); A1, A3, A4, A5 = 0. A2 is fixed in ActCorridorSeparation, so every counter is a hard zero.
            for ((k, v) in totals) check(v == 0) { "$k violated: $summary" }
        }
    })
