@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * PAL Welle X4 measuring gate: notes with the placement hints ABOVE and BELOW (see [NoteHintClearance]). Inputs: the PAL IBD
 * fixtures 41, 42, 46 (negative control, LEFT/RIGHT only) and 47, the activity fixture with ABOVE/BELOW notes, every vault
 * example that carries such a hint and, when present locally, the PAL documents 41, 42, 46, 47, 72 and 74 (read only,
 * copied into a temp dir; skipped on CI).
 *
 * H1 share of honoured hints, H2 width added by hinted notes, H3 leader length of honoured notes. The values measured on the
 * baseline are the upper bounds; measuring output goes to `build/sample-output/note-hints.txt`.
 */
class PalWelleX4NoteHintGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf(
                "41-druckregelung-ibd.md",
                "42-erhitzer-ibd.md",
                "46-erhitzer-sicherheit-ibd.md",
                "47-erhitzer-stb-ibd.md",
                "72-stoerfall-beide-tueren-seq.md",
                "74-raumdruck-hauptalarm-seq.md",
            )

        fun resource(path: String): String =
            PalWelleX4NoteHintGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val fixtures =
                listOf(
                    "pal-ibd/41-druckregelung-ibd",
                    "pal-ibd/42-erhitzer-ibd",
                    "pal-ibd/46-erhitzer-sicherheit-ibd",
                    "pal-ibd/47-erhitzer-stb-ibd",
                ).map { it to resource("/$it.kuml.kts") } +
                    ("sysml2-act-x3/62-67-kombiniert" to resource("/sysml2-act-x3/62-67-kombiniert.kuml.kts").replace("//INCLUDES//", ""))
            val pal =
                if (palDir.isDirectory) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-hint")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val src = File(palDir, name)
                        if (!src.isFile) return@mapNotNull null
                        val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-x4] PAL workspace not found, PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + pal).filter { Regex("""NotePlacement\.(ABOVE|BELOW|LEFT|RIGHT)""").containsMatchIn(it.second) }
        }

        // Baseline (before this wave), measured by this test: hinted ABOVE/BELOW notes, how many are honoured (strict: the note lies
        // on the wished side AND overlaps the anchor box along the axis, H1), pixels the hinted notes add to the width (H2), the
        // width ratio of the diagram to its content, and honoured notes with a long leader (H3). Doc 46 carries only LEFT/RIGHT
        // hints and is the negative control (no ABOVE/BELOW note, nothing may be added). Fixture and PAL document are identical.
        class Baseline(
            val hintedAb: Int,
            val honouredAb: Int,
            val widthPx: Double,
            val widthRatio: Double,
            val longLeaders: Int,
        )
        val baseline =
            mapOf(
                "41-druckregelung-ibd" to Baseline(hintedAb = 5, honouredAb = 3, widthPx = 618.6, widthRatio = 1.664, longLeaders = 3),
                "42-erhitzer-ibd" to Baseline(hintedAb = 4, honouredAb = 3, widthPx = 360.8, widthRatio = 1.457, longLeaders = 3),
                "46-erhitzer-sicherheit-ibd" to Baseline(hintedAb = 0, honouredAb = 0, widthPx = 0.0, widthRatio = 2.609, longLeaders = 0),
                "47-erhitzer-stb-ibd" to Baseline(hintedAb = 1, honouredAb = 0, widthPx = 634.2, widthRatio = 2.485, longLeaders = 0),
            )

        test("measure note placement hints") {
            val out = StringBuilder()
            for ((name, script) in scripts()) {
                val (r, misses) = NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) }
                val svg = r.svg
                if (svg == null) {
                    println("[welle-x4] $name: render failed: ${r.error}")
                    continue
                }
                File("build/sample-output/note-hints")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                val rep = NoteHintClearance.report(svg, script)
                val widthPx = "%.1f".format(rep.widthPx)
                val widthRatio = "%.3f".format(rep.widthRatio)
                val line =
                    "$name  hinted=${rep.hinted} honoured=${rep.honoured}  ab=${rep.hintedAb} ab-honoured=${rep.honouredAb}" +
                        "  w004=${misses.size}  width-px=$widthPx  width-ratio=$widthRatio  long-leaders=${rep.longLeaders.size}"
                println("[welle-x4] $line")
                out.appendLine(line)
                for (n in rep.notes) {
                    val d = "    ${n.noteId} ${n.hint} honoured=${NoteHintClearance.honoured(
                        n,
                    )} rect=${n.rect} box=${n.box} leader=${"%.1f".format(n.leaderLength)}"
                    println("[welle-x4] $d")
                    out.appendLine(d)
                }
                for (m in misses) {
                    println("[welle-x4]     W004 ${m.message()}")
                    out.appendLine("    W004 ${m.message()}")
                }
                // The report of the placer (W004) and the detector must agree per diagram (sequence diagrams use their own placer).
                if (!script.contains("seqDiagram(")) {
                    check(
                        misses.size == rep.hinted - rep.honoured,
                    ) { "$name: W004 ${misses.size} != not honoured ${rep.hinted - rep.honoured}" }
                }
                val base = baseline.entries.firstOrNull { name.endsWith(it.key) }?.value
                if (base != null) {
                    check(rep.hintedAb == base.hintedAb) { "$name: ${rep.hintedAb} ABOVE/BELOW hints, expected ${base.hintedAb}" }
                    check(rep.honouredAb >= base.honouredAb) { "$name: H1 honoured ${rep.honouredAb} < baseline ${base.honouredAb}" }
                    check(rep.widthPx <= base.widthPx + 0.1) { "$name: H2 note width ${rep.widthPx} px > baseline ${base.widthPx}" }
                    check(
                        rep.widthRatio <= base.widthRatio + 0.002,
                    ) { "$name: H2 width ratio ${rep.widthRatio} > baseline ${base.widthRatio}" }
                    check(
                        rep.longLeaders.size <= base.longLeaders,
                    ) { "$name: H3 ${rep.longLeaders.size} long leaders > ${base.longLeaders}" }
                }
            }
            // SEQ measurement (Doc 74 has no hint; a BELOW hint is added to the note s-74 in memory, the workspace stays untouched).
            if (palDir.isDirectory) {
                val src = File(palDir, "74-raumdruck-hauptalarm-seq.md")
                val m = fenced.find(src.readText())
                if (m != null) {
                    val script = m.groupValues[1].replace("id = \"s-74\",", "id = \"s-74\",\n        placement = NotePlacement.BELOW,")
                    val (r, misses) = NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) }
                    val svg = r.svg
                    if (svg != null) {
                        val rep = NoteHintClearance.report(svg, script)
                        for (n in rep.notes) {
                            println(
                                "[welle-x4] SEQ74 ${n.noteId} ${n.hint} honoured=${NoteHintClearance.honoured(
                                    n,
                                )} rect=${n.rect} box=${n.box}",
                            )
                        }
                        for (mm in misses) println("[welle-x4] SEQ74 W004 ${mm.message()}")
                        val rows =
                            Regex("""<g id="message:[^"]+">\s*<(?:path d="M [-0-9.]+ ([-0-9.]+)|line x1="[-0-9.]+" y1="([-0-9.]+)")""")
                                .findAll(svg)
                                .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toDouble() }
                                .toList()
                        println("[welle-x4] SEQ74 message rows y=$rows")
                        File("build/sample-output/note-hints/seq74-below.svg").writeText(svg)
                    } else {
                        println("[welle-x4] SEQ74 render failed: ${r.error}")
                    }
                }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/note-hints.txt").writeText(out.toString())
        }

        // Disabled red tests (Kay: the cases need node moves, which belong to their own wave with sign-off of the existing diagrams).
        // Measured on the baseline of X4: all three ABOVE/BELOW notes that miss their hint sit in a side column beside the content
        // (doc 41: a6-41, m2-41 at x 16..282, anchor box starts at x 330; doc 42: a13e-42 at x 16..266, anchor point x 773; doc 47:
        // a13e-47 at x 933..1193, anchor point x 331) and add 618.6 / 360.8 / 634.2 px of width. Mirrored ABOVE positions stacked above
        // the content were tried in this wave and rejected: 0 of 3 hints gained (the anchors lie inside the content, the leader would
        // have to cross parts), so the only fix is to reserve space for the note in the ELK layer.
        xtest("IBD: every ABOVE/BELOW hint of the PAL fixtures 41, 42 and 47 is honoured (needs a note layer in ELK)") {
            for (n in listOf("41-druckregelung-ibd", "42-erhitzer-ibd", "47-erhitzer-stb-ibd")) {
                val script = resource("/pal-ibd/$n.kuml.kts")
                val rep = NoteHintClearance.report(VaultExampleRenderer.render(script = script).svg!!, script)
                check(rep.honouredAb == rep.hintedAb) { "$n: ${rep.honouredAb} of ${rep.hintedAb} ABOVE/BELOW hints honoured" }
            }
        }

        xtest("SEQ: a BELOW note sits in the x range of its anchor lifeline when the gap below the head allows (needs row reservation)") {
            // Doc 74, note s-74 (hint BELOW added in memory): the note is 61 px high and stands in the right margin column
            // (x 2134.75), 338 px right of the lifeline head (x 1612.75..1795.95); the gap between head bottom (64) and the first
            // message row (128) is 64 px, the note plus the row gap of 12 px needs 73 px.
            val src = File(palDir, "74-raumdruck-hauptalarm-seq.md")
            check(src.isFile) { "PAL workspace not available" }
            val script =
                fenced
                    .find(
                        src.readText(),
                    )!!
                    .groupValues[1]
                    .replace("id = \"s-74\",", "id = \"s-74\",\n        placement = NotePlacement.BELOW,")
            val rep = NoteHintClearance.report(VaultExampleRenderer.render(script = script).svg!!, script)
            val n = rep.notes.single { it.noteId == "s-74" }
            check(n.rect.x < n.box.right && n.rect.right > n.box.x) { "s-74 stands beside its lifeline: ${n.rect}" }
        }
    })
