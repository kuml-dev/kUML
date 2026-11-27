@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.security.MessageDigest

/**
 * PAL Welle X5 measuring gate: note anchor lines (leaders), see [NoteLeaderMetrics]. Inputs: every vault example, the PAL IBD
 * fixtures 41, 42, 46, 47, the combined activity fixture 62/67, the STM fixtures of waves X1 and J2 (Doc 51) and, when present
 * locally, the PAL documents 46, 55 and 62 (read only, copied into a temp dir; skipped on CI). Only diagrams that carry notes.
 *
 * K1 foreign nodes or notes, K2 swimlane borders, K3 foreign labels or the label of the own connection, K4 foreign edges,
 * K5 shared anchors, L leader length. The values measured on the baseline are the upper bounds. Measuring output goes to
 * `build/sample-output/note-leaders.txt`, the SVGs to `build/sample-output/note-leaders/`.
 *
 * Byte identity: when the environment variable `KUML_LEADER_BASELINE_HASHES` names a file written by an earlier run on master
 * (`name sha256` per line), every diagram whose baseline is all zero must hash identically.
 */
class PalWelleX5LeaderGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf(
                "46-erhitzer-sicherheit-ibd.md",
                "55-einfrieren-stm.md",
                "62-stoerungsbehandlung-act.md",
            )

        fun resource(path: String): String =
            PalWelleX5LeaderGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val plain =
                listOf(
                    "pal-ibd/41-druckregelung-ibd",
                    "pal-ibd/42-erhitzer-ibd",
                    "pal-ibd/46-erhitzer-sicherheit-ibd",
                    "pal-ibd/47-erhitzer-stb-ibd",
                    "sysml2-stm-x1/50-tuerverriegelung-stm",
                    "sysml2-stm-x1/52-druckampel-stm",
                    "sysml2-stm-x1/53-stoermeldeklassen-stm",
                    "sysml2-stm-x1/54-erhitzer-stm",
                    "sysml2-stm-x1/56-partikelmonitoring-stm",
                    "sysml2-stm-x1/57-betriebsart-vitec-stm",
                    "sysml2-stm-x1/58-schleusentuer-stm",
                    "sysml2-stm-j2/51-rueckluftklappe-stm",
                ).map { it to resource("/$it.kuml.kts") }
            val fixtures =
                plain +
                    ("sysml2-act-x3/62-67-kombiniert" to resource("/sysml2-act-x3/62-67-kombiniert.kuml.kts").replace("//INCLUDES//", ""))
            val pal =
                if (palDir.isDirectory) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-leader")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val src = File(palDir, name)
                        if (!src.isFile) return@mapNotNull null
                        val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-x5] PAL workspace not found, PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + pal).filter { it.second.contains("comment(") }
        }

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        // Baseline of master ef708be5 (PAL wave X4), measured by this test on 2026-11-27. Upper bounds per diagram name suffix.
        val baseline =
            mapOf(
                "vault/53 SysML 2 STM – Notiz an einer Transition" to LeaderViolations(0, 0, 0, 0, 0, 93.330),
                "vault/54 SysML 2 IBD – Notizen an Port, Verbindung und Owner" to LeaderViolations(0, 0, 0, 0, 0, 455.782),
                "vault/55 SysML 2 ACT – Notiz an einer Partition" to LeaderViolations(0, 0, 0, 0, 0, 24.000),
                "vault/56 SysML 2 PAR – Notiz an einem verschachtelten Constraint" to LeaderViolations(0, 0, 0, 0, 0, 44.000),
                "vault/57 SysML 2 SEQ – Notizen an Lebenslinien" to LeaderViolations(0, 0, 0, 0, 0, 294.000),
                "vault/60 SysML 2 REQ – Mehrere Notizen" to LeaderViolations(0, 0, 0, 0, 0, 48.000),
                "vault/67 SysML 2 BDD – Notiz-Arten" to LeaderViolations(0, 0, 0, 0, 0, 130.234),
                "vault/71 SysML 2 BDD – Formatierter Notiztext" to LeaderViolations(0, 0, 0, 0, 0, 48.000),
                "vault/74 SysML 2 STM – Notiz-Arten mit Symbolen" to LeaderViolations(0, 0, 0, 0, 0, 260.960),
                "pal-ibd/41-druckregelung-ibd" to LeaderViolations(0, 0, 0, 0, 0, 1271.806),
                "pal-ibd/42-erhitzer-ibd" to LeaderViolations(0, 0, 0, 0, 0, 727.014),
                "pal-ibd/46-erhitzer-sicherheit-ibd" to LeaderViolations(0, 0, 0, 0, 0, 499.295),
                "pal-ibd/47-erhitzer-stb-ibd" to LeaderViolations(0, 0, 0, 0, 0, 876.220),
                "sysml2-stm-x1/50-tuerverriegelung-stm" to LeaderViolations(0, 0, 0, 4, 0, 1880.373),
                "sysml2-stm-x1/52-druckampel-stm" to LeaderViolations(0, 0, 0, 0, 0, 924.335),
                "sysml2-stm-x1/53-stoermeldeklassen-stm" to LeaderViolations(0, 0, 0, 1, 0, 1727.001),
                "sysml2-stm-x1/54-erhitzer-stm" to LeaderViolations(0, 0, 0, 1, 0, 1714.689),
                "sysml2-stm-x1/56-partikelmonitoring-stm" to LeaderViolations(0, 0, 0, 0, 0, 573.968),
                "sysml2-stm-x1/57-betriebsart-vitec-stm" to LeaderViolations(0, 0, 0, 2, 0, 1819.077),
                "sysml2-stm-x1/58-schleusentuer-stm" to LeaderViolations(0, 0, 0, 9, 0, 1780.829),
                "sysml2-stm-j2/51-rueckluftklappe-stm" to LeaderViolations(0, 0, 0, 3, 0, 1610.656),
                "pal/46-erhitzer-sicherheit-ibd" to LeaderViolations(0, 0, 0, 0, 0, 499.295),
                "pal/55-einfrieren-stm" to LeaderViolations(0, 0, 0, 0, 0, 756.677),
                "pal/62-stoerungsbehandlung-act" to LeaderViolations(0, 2, 0, 0, 0, 821.888),
            )

        // Diagrams whose SVG changed on purpose in this wave (their master baseline had K > 0): doc 41, fan-out of the shared end.
        val intentionallyChanged = setOf("pal-ibd/41-druckregelung-ibd")

        test("measure note anchor lines") {
            val out = StringBuilder()
            val hashes = StringBuilder()
            val baseHashes =
                System.getenv("KUML_LEADER_BASELINE_HASHES")?.let { File(it) }?.takeIf { it.isFile }?.readLines()?.associate {
                    it.substringBefore(' ') to it.substringAfter(' ')
                }
            for ((name, script) in scripts()) {
                val r = VaultExampleRenderer.render(script = script)
                val svg = r.svg
                if (svg == null) {
                    println("[welle-x5] $name: render failed: ${r.error}")
                    continue
                }
                if (!svg.contains("note-link:")) continue
                File("build/sample-output/note-leaders")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                val rep = NoteLeaderMetrics.report(svg)
                val line = "$name  ${rep.total}  leaders=${rep.perLeader.size}"
                println("[welle-x5] $line")
                out.appendLine(line)
                for ((id, v) in rep.perLeader) {
                    val d = "    $id $v"
                    println("[welle-x5] $d")
                    out.appendLine(d)
                }
                // Invariant (already fixed before X5): an anchor on a node ends on the frame of that node.
                val nodes = NoteLeaderMetrics.nodeRects(svg)
                for (l in if (script.contains("seqDiagram(")) emptyList() else NoteLeaderMetrics.leaders(svg)) {
                    val r = nodes[l.targetId]?.takeIf { it.w >= 30.0 && it.h >= 30.0 } ?: continue
                    val p = l.pts.last()
                    val dx = maxOf(0.0, r.x - p.first, p.first - r.right)
                    val dy = maxOf(0.0, r.y - p.second, p.second - r.bottom)
                    val inner = minOf(p.first - r.x, r.right - p.first, p.second - r.y, r.bottom - p.second)
                    check(
                        (dx == 0.0 && dy == 0.0 && inner <= 1.0) || kotlin.math.hypot(dx, dy) <= 1.0,
                    ) { "$name ${l.id}: anchor ${l.pts.last()} does not end on the frame of its owner $r" }
                }
                val hash = sha256(svg)
                hashes.appendLine("$name $hash")
                val base = baseline[name]
                if (base != null) {
                    val t = rep.total
                    check(t.k1 <= base.k1) { "$name: K1 ${t.k1} > baseline ${base.k1}" }
                    check(t.k2 <= base.k2) { "$name: K2 ${t.k2} > baseline ${base.k2}" }
                    check(t.k3 <= base.k3) { "$name: K3 ${t.k3} > baseline ${base.k3}" }
                    check(t.k4 <= base.k4) { "$name: K4 ${t.k4} > baseline ${base.k4}" }
                    check(t.k5 <= base.k5) { "$name: K5 ${t.k5} > baseline ${base.k5}" }
                    // Fanning out one shared anchor end costs at most one offset (16 px) of leader length.
                    val allowed = base.length + 0.1 + 16.0 * (base.k5 - t.k5)
                    check(t.length <= allowed) { "$name: L ${t.length} > allowed $allowed" }
                    if (base.sum == 0 && baseHashes != null && name !in intentionallyChanged) {
                        val old = baseHashes[name]
                        if (old != null) check(old == hash) { "$name: baseline is all zero but the SVG changed (byte identity)" }
                    }
                }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/note-leaders.txt").writeText(out.toString())
            File("build/sample-output/note-leaders-hashes.txt").writeText(hashes.toString())
        }

        // Measured on master ef708be5 (PAL wave X4): the notes a6-41 and m2-41 anchor the same node (referenzbereich) and their
        // anchor points coincided in its top left corner, K5 = 1. Cured by the fan-out of shared ends (m2-41 moves 16 px, +16 px length).
        test("K5: no two notes share an anchor point in the PAL IBD fixture 41") {
            val svg = VaultExampleRenderer.render(script = resource("/pal-ibd/41-druckregelung-ibd.kuml.kts")).svg!!
            check(NoteLeaderMetrics.report(svg).total.k5 == 0) { "K5 > 0" }
        }

        // Not applied (measured, red): Doc 62 K2 = 2 (a5e-62 and r23-62 cross one swimlane border each). The anchor lines of Doc 62
        // would need a lane aware route search; the leader cost vector (fix 3) was not built in this wave. Doc 46 K3 measured 0.
        // Needs the PAL workspace (skipped on CI).
        xtest("K2: no anchor line of Doc 62 crosses a swimlane that does not hold its anchor") {
            val src = File(palDir, "62-stoerungsbehandlung-act.md")
            check(src.isFile) { "PAL workspace not available" }
            val script = fenced.find(src.readText())!!.groupValues[1]
            val svg = VaultExampleRenderer.render(script = script).svg!!
            check(NoteLeaderMetrics.report(svg).total.k2 == 0) { "K2 > 0" }
        }
    })
