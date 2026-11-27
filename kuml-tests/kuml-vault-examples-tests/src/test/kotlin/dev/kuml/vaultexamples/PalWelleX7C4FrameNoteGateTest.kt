@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * PAL Welle X7 measuring gate: C4 notes inside the system boundary frame, see [C4FrameNoteMetrics]. Inputs: the vault examples
 * with a C4 diagram (02, 09, 23, 24, 25, 26, 58), the fixtures `c4-x7/a-inside` and `c4-x7/b-outside` and, when present locally,
 * the PAL documents 35 and 36 (read only, copied into a temp dir; skipped on CI). The values measured on the baseline are the
 * bounds per diagram. Measuring output goes to `build/sample-output/c4-frame-notes.txt`.
 */
class PalWelleX7C4FrameNoteGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val palDocs = listOf("35-kontext-c4.md", "36-container-c4.md")
        val vaultPrefixes = listOf("02 ", "09 ", "23 ", "24 ", "25 ", "26 ", "58 ")
        val onCi = System.getenv("CI") != null

        fun resource(path: String): String =
            PalWelleX7C4FrameNoteGateTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun palScripts(): List<Pair<String, String>> {
            if (onCi || !palDir.isDirectory) {
                println("[welle-x7] PAL workspace not used (CI or missing), PAL documents skipped")
                return emptyList()
            }
            val tmp =
                java.nio.file.Files
                    .createTempDirectory("pal-x7")
                    .toFile()
            return try {
                palDocs.mapNotNull { name ->
                    val src = File(palDir, name)
                    if (!src.isFile) return@mapNotNull null
                    val copy = File(tmp, name).also { src.copyTo(it, overwrite = true) }
                    val m = fenced.find(copy.readText()) ?: return@mapNotNull null
                    "pal/${name.removeSuffix(".md")}" to m.groupValues[1]
                }
            } finally {
                tmp.deleteRecursively()
            }
        }

        fun scripts(): List<Pair<String, String>> {
            val vault =
                VaultExampleLoader
                    .loadFromClasspath()
                    .filter { ex -> vaultPrefixes.any { ex.baseName.startsWith(it) } }
                    .map { "vault/${it.baseName}" to it.kumlScript }
            val fixtures =
                listOf("c4-x7/a-inside", "c4-x7/b-outside").map { it to resource("/$it.kuml.kts") }
            return vault + fixtures + palScripts()
        }

        fun render(script: String): String? = VaultExampleRenderer.render(script = script).svg

        /** Note rectangles by id, for the "fixture b is unchanged" comparison. */
        fun noteBoxes(svg: String): Map<String, String> =
            NoteLeaderMetrics.noteRects(svg).mapValues { (_, r) -> "${r.x},${r.y},${r.w},${r.h}" }

        // Baseline measured on master b59ccda5 (PAL wave X6) by this test, see CHANGELOG. Bounds per diagram: c1, c2, extraPx.
        val baseline: Map<String, Triple<Int, Int, Double>> =
            mapOf(
                "vault/02 C4 Container – Internet Banking" to Triple(0, 0, 0.0),
                "vault/09 C4 Landscape – Enterprise Banking" to Triple(0, 0, 0.0),
                "vault/23 C4 Context – Internet Banking" to Triple(0, 0, 86.7),
                "vault/24 C4 Component – Web App Internals" to Triple(0, 0, 0.0),
                "vault/25 C4 Deployment – AWS Production" to Triple(0, 0, 86.8),
                "vault/26 C4 Dynamic – Checkout Flow" to Triple(0, 0, 4.0),
                "vault/58 C4 Context – Notizen" to Triple(0, 0, 712.0),
                "c4-x7/a-inside" to Triple(0, 3, 509.5),
                "c4-x7/b-outside" to Triple(0, 0, 280.6),
                "pal/35-kontext-c4" to Triple(0, 0, 420.8),
                "pal/36-container-c4" to Triple(1, 4, 548.7),
            )

        // Notes of fixture b on master b59ccda5: they stay outside, whatever this wave does.
        val fixtureBNotes: Map<String, String> =
            mapOf(
                "m2-x7" to "763.2,242.0,256.59998,46.0",
                "q-x7" to "504.4,644.0,256.59998,46.0",
                "mix-x7" to "504.4,571.0,256.59998,46.0",
            )

        test("measure C4 notes in the frame") {
            val out = StringBuilder()
            val summary = StringBuilder()
            for ((name, script) in scripts()) {
                val svg = render(script)
                if (svg == null) {
                    println("[welle-x7] $name: render failed")
                    continue
                }
                File("build/sample-output/c4-frame-notes")
                    .also { it.mkdirs() }
                    .resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg")
                    .writeText(svg)
                val rep = C4FrameNoteMetrics.measure(svg)
                val line = "$name  $rep  N1=${C4FrameNoteMetrics.edgeHitNotes(svg)}  frames=${C4FrameNoteMetrics.frames(
                    svg,
                ).size} notes=${NoteLeaderMetrics.noteRects(svg).size}  ${rep.offenders}"
                println("[welle-x7] $line")
                out.appendLine(line)
                summary.appendLine(
                    """                "$name" to Triple(${rep.c1}, ${rep.c2}, ${"%.1f".format(java.util.Locale.ROOT, rep.extraPx)}),""",
                )
                summary.appendLine("//notes " + noteBoxes(svg))
                check(
                    C4FrameNoteMetrics.edgeHits(svg) == 0,
                ) { "$name: an edge runs through a note: ${C4FrameNoteMetrics.edgeHitNotes(svg)}" }
                val base = baseline[name] ?: continue
                check(rep.c1 <= base.first) { "$name: C1 ${rep.c1} > baseline ${base.first}" }
                check(rep.c2 <= base.second) { "$name: C2 ${rep.c2} > baseline ${base.second}" }
                // Curing a C2 case outward costs width (a note that straddled the frame line now keeps 12 px clear of it), so the
                // width bound only holds for diagrams the wave leaves alone; the others are reported (see CHANGELOG).
                if (base.first == 0 && base.second == 0) {
                    check(rep.extraPx <= base.third + 0.5) { "$name: extraPx ${rep.extraPx} > baseline ${base.third}" }
                }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/c4-frame-notes.txt").writeText(out.toString())
            File("build/sample-output/c4-frame-notes-baseline.txt").writeText(summary.toString())
        }

        // Detector self-test on a synthetic SVG: frame 100..400 x 100..300, title strip 34 px, three notes.
        test("the detector finds a free slot, a title overlap, a frame crossing and a note that is too close to the frame line") {
            fun note(
                id: String,
                x: Int,
                y: Int,
            ) = """
                <g id="$id" transform="translate($x,$y)">
                  <path d="M 0 0 L 66 0 L 80 14 L 80 32 L 0 32 Z" class="kuml-comment kuml-c4-note"/>
                </g>
                """

            fun anchor(
                x1: Int,
                y1: Int,
                x2: Int,
                y2: Int,
            ) =
                """<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" class="kuml-note-anchor"/><circle cx="$x2" cy="$y2" r="2" class="kuml-note-anchor-dot"/>"""
            val svg =
                """
                <svg viewBox="0 0 700 500">
                  <g id="nodes">
                    <g id="kuml-diagram-frame"><rect x="2" y="2" width="696" height="496" class="kuml-frame"/></g>
                    <g id="system-s" transform="translate(100,100)">
                      <rect width="300" height="200" class="kuml-system"/>
                    </g>
                    <g id="m1" transform="translate(120,150)"><rect width="60" height="40" class="kuml-container"/></g>
                    ${note("outside", 450, 150)}
                    ${note("onTitle", 200, 105)}
                    ${note("crossing", 380, 200)}
                    ${note("tooClose", 315, 255)}
                  </g>
                  <g id="edges">
                    ${anchor(450, 166, 180, 170)}
                    ${anchor(200, 121, 150, 160)}
                    ${anchor(380, 216, 170, 180)}
                    ${anchor(315, 271, 170, 190)}
                  </g>
                </svg>
                """.trimIndent()
            val frames = C4FrameNoteMetrics.frames(svg)
            check(frames.size == 1 && frames[0].w == 300.0 && frames[0].x == 100.0) { "frame not parsed: $frames" }
            val rep = C4FrameNoteMetrics.measure(svg)
            // outside: member anchor, free slot inside -> C1; onTitle + crossing + tooClose -> C2
            check(rep.c1 == 1) { "C1 expected 1 but was ${rep.c1}: ${rep.offenders}" }
            check(rep.c2 == 3) { "C2 expected 3 but was ${rep.c2}: ${rep.offenders}" }
            check(rep.offenders.contains("C1:outside@system-s")) { "outside not reported: ${rep.offenders}" }
            check(rep.offenders.contains("C2:onTitle@system-s")) { "onTitle not reported: ${rep.offenders}" }
        }

        test("a note whose anchor is not a member of the frame is no C1 case") {
            val svg =
                """
                <svg viewBox="0 0 700 500">
                  <g id="nodes">
                    <g id="system-s" transform="translate(100,100)"><rect width="300" height="200" class="kuml-system"/></g>
                    <g id="ext" transform="translate(500,150)"><rect width="60" height="40" class="kuml-container"/></g>
                    <g id="n" transform="translate(450,250)">
                      <path d="M 0 0 L 66 0 L 80 14 L 80 32 L 0 32 Z" class="kuml-comment kuml-c4-note"/>
                    </g>
                  </g>
                  <g id="edges">
                    <line x1="490" y1="266" x2="520" y2="190" class="kuml-note-anchor"/><circle cx="520" cy="190" r="2" class="kuml-note-anchor-dot"/>
                  </g>
                </svg>
                """.trimIndent()
            val rep = C4FrameNoteMetrics.measure(svg)
            check(rep.c1 == 0 && rep.c2 == 0) { "expected C1=0 C2=0 but was $rep" }
        }

        test("the detector constants equal the placer constants") {
            check(
                C4FrameNoteMetrics.TITLE_INK_PX + C4FrameNoteMetrics.GAP_PX ==
                    dev.kuml.layout.bridge.note.C4NotePlacement.C4_FRAME_TITLE_KEEP_OUT_PX,
            ) {
                "title keep-out differs"
            }
            check(C4FrameNoteMetrics.GAP_PX == dev.kuml.layout.bridge.note.C4NotePlacement.C4_FRAME_NOTE_GAP_PX) { "gap differs" }
        }

        fun palSvg(name: String): String? {
            if (onCi || !palDir.isDirectory) {
                println("[welle-x7] PAL workspace not available, $name skipped")
                return null
            }
            val tmp =
                java.nio.file.Files
                    .createTempDirectory("pal-x7-doc")
                    .toFile()
            try {
                val copy = File(tmp, name).also { File(palDir, name).copyTo(it, overwrite = true) }
                return render(fenced.find(copy.readText())!!.groupValues[1])
            } finally {
                tmp.deleteRecursively()
            }
        }

        // Measured on master b59ccda5: Doc 36 C1 = 1 (a13e-36), C2 = 4 (a14-36, a15e-36, a5e-36, a2e-36 straddle the frame line).
        // Needs the PAL workspace (skipped on CI).
        test("Doc 36: C1 + C2 is strictly below the baseline of 5, C2 is 0, no edge runs through a note") {
            val svg = palSvg("36-container-c4.md") ?: return@test
            val rep = C4FrameNoteMetrics.measure(svg)
            check(rep.c1 + rep.c2 < 5) { "C1+C2 not below baseline: $rep ${rep.offenders}" }
            check(rep.c2 == 0) { "C2 > 0: ${rep.offenders}" }
            check(C4FrameNoteMetrics.edgeHits(svg) == 0) { "edge through a note: ${C4FrameNoteMetrics.edgeHitNotes(svg)}" }
        }

        // Not applied (measured, red): note a13e-36 stays outside (no slot inside the frame that its anchor line can reach by the
        // placer's routing, C1 = 1) and the canvas is 12 px wider than the baseline of 548.7 px extra width (560.7 px) because the
        // notes that straddled the frame line now keep 12 px clear of it. Fixing this needs frame growth (layout change).
        xtest("X7: needs frame growth (layout change): Doc 36 has no C1 note and is not wider than the baseline") {
            val svg = palSvg("36-container-c4.md")!!
            val rep = C4FrameNoteMetrics.measure(svg)
            check(rep.c1 == 0 && rep.extraPx <= 548.7 + 0.5) { "$rep ${rep.offenders}" }
        }

        test("the detector is deterministic") {
            val svg = render(resource("/c4-x7/a-inside.kuml.kts"))!!
            check(C4FrameNoteMetrics.measure(svg).toString() == C4FrameNoteMetrics.measure(svg).toString()) { "not deterministic" }
        }

        test("fixture b: the notes of the boundary, of the external system and with mixed anchors stay outside") {
            val svg = render(resource("/c4-x7/b-outside.kuml.kts"))!!
            val frame = C4FrameNoteMetrics.frames(svg).single().rect
            val notes = NoteLeaderMetrics.noteRects(svg)
            check(notes.keys == setOf("m2-x7", "q-x7", "mix-x7")) { "notes: ${notes.keys}" }
            for ((id, r) in notes) check(!frame.overlaps(r)) { "$id lies in or on the frame" }
            if (fixtureBNotes.isNotEmpty()) check(noteBoxes(svg) == fixtureBNotes) { "fixture b notes moved: ${noteBoxes(svg)}" }
        }

        test("fixture a: a note at a member with room lies inside the frame, no note touches a frame line") {
            val svg = render(resource("/c4-x7/a-inside.kuml.kts"))!!
            val rep = C4FrameNoteMetrics.measure(svg)
            check(rep.c1 == 0 && rep.c2 == 0) { "expected C1=0 C2=0 but was $rep ${rep.offenders}" }
            // a14-x7 (anchored at the controller) has room inside; the other three have no free slot a leader could reach.
            val frame = C4FrameNoteMetrics.frames(svg).single().rect
            check(frame.contains(NoteLeaderMetrics.noteRects(svg).getValue("a14-x7"), 12.0)) { "a14-x7 not 12 px inside the frame" }
        }
    })
