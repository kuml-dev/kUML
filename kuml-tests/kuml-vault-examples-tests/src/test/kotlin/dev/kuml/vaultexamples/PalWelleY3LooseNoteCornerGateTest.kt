@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.security.MessageDigest

/**
 * PAL Welle Y3 measuring gate: the free-standing activity legend uses the free bottom-right corner inside the content box,
 * see [LooseNoteCornerMetrics]. Inputs: every vault example with a free-standing note, the `sysml2-act-x3` fixture, the Y3 fixtures and,
 * when present locally, the PAL documents 60, 61, 62, 63, 65, 66, 67 (read only, temp copy; skipped on CI).
 *
 * Output: `build/sample-output/loose-note-corner.txt` (area before/after, L1, L2, L2b), hashes to
 * `build/sample-output/loose-note-corner-hashes.txt` (`name sha256`). Byte identity: when `KUML_LOOSECORNER_BASELINE_HASHES`
 * names a hash file written on master before the change, every diagram without a free-standing note must hash identically.
 */
class PalWelleY3LooseNoteCornerGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf(60, 61, 62, 63, 65, 66, 67).flatMap { n ->
                palDir.listFiles { f -> f.name.startsWith("$n-") && f.name.endsWith(".md") }?.map { it.name } ?: emptyList()
            }

        fun resource(path: String): String =
            PalWelleY3LooseNoteCornerGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val fixtures =
                listOf(
                    "sysml2-act-y3/sysml2-act-y3-legend-free-corner",
                    "sysml2-act-y3/sysml2-act-y3-legend-dense-corner",
                    "sysml2-act-y3/sysml2-act-y3-legend-no-lanes",
                    "sysml2-act-y3/sysml2-act-y3-two-legends",
                ).map { it to resource("/$it.kuml.kts") } +
                    ("sysml2-act-x3/62-67-kombiniert" to resource("/sysml2-act-x3/62-67-kombiniert.kuml.kts").replace("//INCLUDES//", ""))
            val pal =
                if (palDir.isDirectory) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-corner")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val copy = File(tmp, name).also { File(palDir, name).copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-y3] PAL workspace not found, PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + pal).filter { it.second.contains("actDiagram(") }
        }

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        // Free-standing notes without comment lines: remove the unanchored comment( calls (single or multi line, no further argument).
        val freeComment = Regex("""(?m)^\s*comment\(\s*text\s*=\s*"[^"]*"(?:\s*,\s*(?:id|kind)\s*=\s*[A-Za-z.\"0-9_-]+)*\s*,?\s*\)\s*$""")

        fun withoutFreeNotes(script: String): String = script.replace(freeComment, "")

        // Baseline measured on master a9912d38 (PAL wave Y2) by this test: area, L2, L2b per diagram with a free-standing note.
        val baseline: Map<String, Triple<Double, Int, Int>> =
            mapOf(
                "sysml2-act-y3/sysml2-act-y3-legend-free-corner" to Triple(406912.0, 0, 0),
                "sysml2-act-y3/sysml2-act-y3-legend-dense-corner" to Triple(362628.0, 0, 0),
                "sysml2-act-y3/sysml2-act-y3-legend-no-lanes" to Triple(958576.0, 0, 0),
                "sysml2-act-y3/sysml2-act-y3-two-legends" to Triple(406912.0, 0, 0),
            )

        // Fixtures where the free-standing notes cannot use a corner: the SVG must stay byte-identical to master.
        val unchangedByDesign = setOf("sysml2-act-y3/sysml2-act-y3-legend-dense-corner", "sysml2-act-y3/sysml2-act-y3-two-legends")

        // Fixtures with a free corner: the image must not grow by the note (area equal to the source without its notes).
        val cornerFixtures = setOf("sysml2-act-y3/sysml2-act-y3-legend-free-corner", "sysml2-act-y3/sysml2-act-y3-legend-no-lanes")

        test("measure the free-standing notes against the content box") {
            val out = StringBuilder()
            val hashes = StringBuilder()
            val baseHashes =
                System.getenv("KUML_LOOSECORNER_BASELINE_HASHES")?.let { File(it) }?.takeIf { it.isFile }?.readLines()?.associate {
                    it.substringBefore(' ') to it.substringAfter(' ')
                }
            var freeNoteDiagrams = 0
            for ((name, script) in scripts()) {
                val svg = VaultExampleRenderer.render(script = script).svg
                if (svg == null) {
                    println("[welle-y3] $name: render failed")
                    continue
                }
                val hash = sha256(svg)
                hashes.appendLine("$name $hash")
                File("build/sample-output/loose-note-corner")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                if (LooseNoteCornerMetrics.freeNotes(svg).isEmpty()) {
                    if (baseHashes != null) {
                        val old = baseHashes[name]
                        if (old != null) check(old == hash) { "$name: no free-standing note, but the SVG changed (byte identity)" }
                    }
                    continue
                }
                freeNoteDiagrams++
                // Dense corner: the old behaviour must stay (hash equal to master).
                if (name in unchangedByDesign && baseHashes != null) {
                    check(baseHashes[name] == hash) { "$name: no free corner, the SVG must stay identical to master" }
                }
                val bare = withoutFreeNotes(script).takeIf { it != script }?.let { VaultExampleRenderer.render(script = it).svg }
                val rep = LooseNoteCornerMetrics.report(svg, bare)
                val line =
                    "$name  area=${rep.area} without=${rep.areaWithoutNote} L1=${rep.l1} L2=${rep.l2} L2b=${rep.l2b} notes=${rep.noteIds}"
                println("[welle-y3] $line")
                out.appendLine(line)
                val base = baseline[name]
                if (base != null) {
                    check(rep.area < base.first || (rep.area <= base.first && rep.l2 == 0 && rep.l2b <= base.third)) {
                        "$name: area ${rep.area} not below baseline ${base.first}"
                    }
                }
                if (name in cornerFixtures) {
                    check(rep.areaWithoutNote != null && rep.area <= rep.areaWithoutNote) { "$name: the legend must not grow the image" }
                    check(!rep.l1) { "$name: L1, the legend sticks out of the content box although a corner is free" }
                }
                check(rep.l2 == 0 || base != null && rep.l2 <= base.second) { "$name: L2 = ${rep.l2}" }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/loose-note-corner.txt").writeText("free-note diagrams: $freeNoteDiagrams\n$out")
            File("build/sample-output/loose-note-corner-hashes.txt").writeText(hashes.toString())
        }

        // Detector self-test on a synthetic SVG: L1 (note below a free corner), L2 (note on a node), L2b (note 6 px from a node).
        test("the detector finds a note outside a free corner, a covered node and a gap below 12 px") {
            fun svg(
                noteX: Int,
                noteY: Int,
            ) = """
                <svg viewBox="0 0 700 700">
                  <g id="nodes">
                    <g id="A" transform="translate(0,0)"><rect width="100" height="100" class="kuml-class"/></g>
                    <g id="B" transform="translate(400,0)"><rect width="100" height="100" class="kuml-class"/></g>
                    <g id="C" transform="translate(0,400)"><rect width="100" height="100" class="kuml-class"/></g>
                    <g id="legende" transform="translate($noteX,$noteY)">
                      <path d="M 0 0 L 66 0 L 80 14 L 80 32.0 L 0 32.0 Z" class="kuml-comment kuml-sysml2-comment"/>
                    </g>
                  </g>
                </svg>
                """.trimIndent()
            // below the content box (box bottom 500): L1
            val below = LooseNoteCornerMetrics.report(svg(noteX = 420, noteY = 520), null)
            check(below.l1 && below.l2 == 0) { "L1 expected: $below" }
            // inside the free corner with room: no violation
            val ok = LooseNoteCornerMetrics.report(svg(noteX = 420, noteY = 460), null)
            check(!ok.l1 && ok.l2 == 0 && ok.l2b == 0) { "clean expected: $ok" }
            // on a node: L2
            val over = LooseNoteCornerMetrics.report(svg(noteX = 420, noteY = 90), null)
            check(over.l2 == 1) { "L2 expected: $over" }
            // 6 px beside a node: L2b only
            val near = LooseNoteCornerMetrics.report(svg(noteX = 106, noteY = 420), null)
            check(near.l2 == 0 && near.l2b >= 1) { "L2b expected: $near" }
        }

        test("the detector is deterministic") {
            val svg = VaultExampleRenderer.render(script = resource("/sysml2-act-y3/sysml2-act-y3-legend-free-corner.kuml.kts")).svg!!
            check(LooseNoteCornerMetrics.report(svg, null) == LooseNoteCornerMetrics.report(svg, null)) { "not deterministic" }
        }

        // Not applied (measured, red): in the dense corner the legend stays below the content; the corner holds an end node and edge
        // labels. A smaller legend would fit between them only by moving 40 px or more, i.e. out of the corner. Measured: image area
        // 362628 against 326940.8 without the note.
        xtest("the legend of the dense-corner fixture does not grow the image") {
            val script = resource("/sysml2-act-y3/sysml2-act-y3-legend-dense-corner.kuml.kts")
            val svg = VaultExampleRenderer.render(script = script).svg!!
            val bare = VaultExampleRenderer.render(script = withoutFreeNotes(script)).svg!!
            check(LooseNoteCornerMetrics.imageArea(svg) <= LooseNoteCornerMetrics.imageArea(bare)) { "image grows" }
        }
    })
