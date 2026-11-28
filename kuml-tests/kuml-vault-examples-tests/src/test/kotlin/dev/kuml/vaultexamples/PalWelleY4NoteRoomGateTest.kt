@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.IbdNoteRoomProbe
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.security.MessageDigest

/**
 * PAL Welle Y4 measuring gate: the note room for `ABOVE` / `BELOW` hints in the IBD layout. Every IBD input is rendered twice
 * in the same JVM, once with the gate off (the layout as before) and once with it on, and compared:
 *
 * - SHA-256 guard: only the diagrams of the allowed set (the PAL fixtures 41, 42, 47) may differ between the two runs; every
 *   vault example and every other fixture must be byte-identical.
 * - a diagram where the gate took the second layout must have a strictly higher H1 (honoured ABOVE/BELOW hints), no wider
 *   diagram (H2) and no larger drawing area.
 * - the layout findings do not depend on the gate (the second layout reports none).
 *
 * Inputs: the vault examples, the PAL IBD fixtures 41, 42, 46, 47 and, when present locally, the PAL documents 41, 42, 46, 47
 * (read only, copied into a temp dir; skipped on CI). The table goes to `build/sample-output/note-room-y4.txt`.
 */
class PalWelleY4NoteRoomGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs =
            listOf("41-druckregelung-ibd.md", "42-erhitzer-ibd.md", "46-erhitzer-sicherheit-ibd.md", "47-erhitzer-stb-ibd.md")

        /** Diagrams the gate may change: the fixtures (and PAL documents) 41, 42, 47. */
        val allowedToDiffer = listOf("41-druckregelung-ibd", "42-erhitzer-ibd", "47-erhitzer-stb-ibd")

        fun resource(path: String): String =
            PalWelleY4NoteRoomGateTest::class.java
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
                ).map { it to resource("/$it.kuml.kts") }
            val pal =
                if (palDir.isDirectory && System.getenv("CI") == null) {
                    val tmp =
                        java.nio.file.Files
                            .createTempDirectory("pal-room")
                            .toFile()
                    palDocs.mapNotNull { name ->
                        val src = File(palDir, name)
                        if (!src.isFile) return@mapNotNull null
                        val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                        val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                        "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                    }
                } else {
                    println("[welle-y4] PAL workspace not used (missing or CI), PAL documents skipped")
                    emptyList()
                }
            return (vault + fixtures + pal).filter { Regex("""\bibd\(""").containsMatchIn(it.second) }
        }

        fun sha(svg: String): String =
            MessageDigest.getInstance("SHA-256").digest(svg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        class Run(
            val svg: String,
            val misses: Int,
            val missMessages: List<String>,
            val findings: List<String>,
            val decisions: List<IbdNoteRoomProbe.Decision>,
        )

        fun render(
            script: String,
            gate: Boolean,
            forceSecond: Boolean = false,
        ): Run? {
            val (collected, decisions) =
                IbdNoteRoomProbe.collecting(gate = gate, forceSecond = forceSecond) {
                    VaultExampleRenderer.collectingFindings {
                        NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) }
                    }
                }
            val (rendered, findings) = collected
            val svg = rendered.first.svg ?: return null
            return Run(
                svg = svg,
                misses = rendered.second.count { it.code == null },
                missMessages = rendered.second.map { it.message() },
                findings = findings.map { it.code }.sorted(),
                decisions = decisions,
            )
        }

        test("measure the note room gate and guard every other diagram") {
            val out = StringBuilder()
            val differing = mutableListOf<String>()
            for ((name, script) in scripts()) {
                val off = render(script = script, gate = false)
                val on = render(script = script, gate = true)
                if (off == null || on == null) {
                    println("[welle-y4] $name: render failed")
                    continue
                }
                val repOff = NoteHintClearance.report(off.svg, script)
                val repOn = NoteHintClearance.report(on.svg, script)
                val same = sha(off.svg) == sha(on.svg)
                val classes = VaultExampleRenderer.ibdNoteEligibility(script)
                val line =
                    "$name  same=$same  ab-hinted=${repOff.hintedAb}  H1 off=${repOff.honouredAb} on=${repOn.honouredAb}" +
                        "  H2 width-px off=${"%.1f".format(repOff.widthPx)} on=${"%.1f".format(repOn.widthPx)}" +
                        "  width-ratio off=${"%.3f".format(repOff.widthRatio)} on=${"%.3f".format(repOn.widthRatio)}" +
                        "  area off=${"%.0f".format(
                            repOff.svgWidth * repOff.svgHeight,
                        )} on=${"%.0f".format(repOn.svgWidth * repOn.svgHeight)}" +
                        "  w004 off=${off.misses} on=${on.misses}"
                println("[welle-y4] $line")
                out.appendLine(line)
                for ((id, cls) in classes) {
                    println("[welle-y4]     note $id: $cls")
                    out.appendLine("    note $id: $cls")
                }
                for (d in on.decisions) {
                    println(
                        "[welle-y4]     gate ${d.diagram}: reserved=${d.reservedNoteIds} takenB=${d.takenB} (${d.reason}) misses ${d.missesA}->${d.missesB} area ${d.areaA}->${d.areaB}",
                    )
                    out.appendLine(
                        "    gate ${d.diagram}: reserved=${d.reservedNoteIds} takenB=${d.takenB} (${d.reason}) misses ${d.missesA}->${d.missesB} area ${d.areaA}->${d.areaB}",
                    )
                }
                if (on.decisions.isNotEmpty()) {
                    // Diagnostic: what the second layout looks like when it is kept whatever it measures.
                    val forced = render(script = script, gate = true, forceSecond = true)
                    if (forced != null) {
                        File("build/sample-output/note-room-y4")
                            .also { it.mkdirs() }
                            .resolve(
                                name.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-forced.svg",
                            ).writeText(forced.svg)
                        val rep = NoteHintClearance.report(forced.svg, script)
                        for (n in rep.notes) {
                            val d = "    forced-B ${n.noteId} ${n.hint} honoured=${NoteHintClearance.honoured(
                                n,
                            )} rect=${n.rect} box=${n.box}"
                            println("[welle-y4] $d")
                            out.appendLine(d)
                        }
                    }
                    for (n in repOff.notes) {
                        val d = "    A ${n.noteId} ${n.hint} honoured=${NoteHintClearance.honoured(n)} rect=${n.rect} box=${n.box}"
                        println("[welle-y4] $d")
                        out.appendLine(d)
                    }
                }
                // The findings of the kept run are reported once: with run A kept they equal those of the gate-off render, and the
                // render is deterministic (a second render with the gate on gives the same bytes).
                if (same) {
                    check(
                        off.missMessages == on.missMessages,
                    ) { "$name: W004/IBD findings differ: ${off.missMessages} vs ${on.missMessages}" }
                }
                check(render(script = script, gate = true)!!.svg == on.svg) { "$name: the gated render is not deterministic" }
                check(off.findings == on.findings) { "$name: layout findings depend on the gate: ${off.findings} vs ${on.findings}" }
                if (!same) {
                    differing += name
                    check(allowedToDiffer.any { name.endsWith(it) }) { "$name changed although it is outside the allowed set" }
                    check(on.decisions.any { it.takenB }) { "$name differs without a gate decision for run B" }
                    check(repOn.honouredAb > repOff.honouredAb) { "$name: H1 ${repOn.honouredAb} is not above ${repOff.honouredAb}" }
                    check(
                        repOn.widthRatio <= repOff.widthRatio + 0.002,
                    ) { "$name: H2 width ratio ${repOn.widthRatio} > ${repOff.widthRatio}" }
                    check(repOn.svgWidth * repOn.svgHeight <= repOff.svgWidth * repOff.svgHeight + 0.5) { "$name: drawing area grew" }
                } else {
                    check(on.decisions.none { it.takenB }) { "$name: gate took run B but the SVG is unchanged" }
                }
            }
            out.appendLine("differing: $differing")
            println("[welle-y4] differing: $differing")
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/note-room-y4.txt").writeText(out.toString())
        }
    })
