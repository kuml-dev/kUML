package dev.kuml.vaultexamples

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.LabelRole
import dev.kuml.layout.bridge.label.PortLabelAudit
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL Welle U: how much room the finished label plan of the PAL IBD fixtures leaves between labels and parts, other labels
 * and foreign connectors. Counters (see [IbdLabelClearance] for the exact definitions; threshold [IbdLabelClearance.MIN_GAP]
 * = 6.0 px between a label and a foreign part, overlaps counted above 0.5 px, a part's owners and their ancestors are not
 * foreign):
 *
 * - C1 `KUML-IBD-W002` reports, C2 connector or flow label overlaps a foreign part, C3 label overlaps label,
 *   C4 label hit by a foreign connector, C5 label closer than 6 px to a foreign part.
 *
 * Source of the numbers: the final label plan the production renderer builds ([PortLabelAudit]), plan rectangles, no SVG
 * glyphs and no fonts, so the result is the same on every platform.
 *
 * Measured 2026-11-26 on Doc 41, 42, 46, 47 and the Box-in-Box fixture: C1 to C4 are 0 everywhere. C5 was 0 everywhere except
 * the port labels `stellsignal` of the FMCS (4.5 px below the part `schleusenraum`) and of the RLK (2.0 px below the part
 * `vsr`). Since PAL Welle V the label placer has a second placement pass that moves a port label that stands closer than
 * `LabelPlacer.PORT_PART_CLEAR_PX` (6 px, the value of [IbdLabelClearance.MIN_GAP]) to a foreign part, so C1 to C5 are 0 on
 * every fixture and the test pins exactly that.
 */
class PalWelleUIbdLabelClearanceInvariantTest :
    FunSpec({
        timeout = 300_000L
        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

        fun resource(path: String) =
            PalWelleUIbdLabelClearanceInvariantTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun fixtures(): List<Pair<String, String>> =
            listOf("41-druckregelung-ibd", "42-erhitzer-ibd", "46-erhitzer-sicherheit-ibd", "47-erhitzer-stb-ibd")
                .map { "pal-ibd/$it" to resource("/pal-ibd/$it.kuml.kts") } +
                fenced
                    .findAll(resource("/ibd-fixtures/IBD Box-in-Box 3 Ebenen.md"))
                    .mapIndexed { i, m -> "ibd-fixtures/box-in-box-$i" to m.groupValues[1] }
                    .toList()

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        fun synthetic(label: Rect): PortLabelAudit.Snapshot =
            PortLabelAudit.Snapshot(
                ports = emptyList(),
                otherLabelViolations = 0,
                labelCount = 1,
                labels =
                    listOf(
                        PortLabelAudit.LabelEntry(
                            id = "conn:T::E1",
                            role = LabelRole.EDGE_CENTER,
                            ownerId = "conn:T::E1",
                            parentId = null,
                            rect = label,
                        ),
                    ),
                obstacles =
                    listOf(
                        PortLabelAudit.ObstacleEntry(
                            kind = ObstacleKind.NODE,
                            ownerId = "T::a",
                            rect = rect(x = 0f, y = 0f, w = 100f, h = 50f),
                        ),
                        PortLabelAudit.ObstacleEntry(
                            kind = ObstacleKind.NODE,
                            ownerId = "T::far",
                            rect = rect(x = 0f, y = 500f, w = 100f, h = 50f),
                        ),
                        PortLabelAudit.ObstacleEntry(
                            kind = ObstacleKind.NODE,
                            ownerId = "T::x",
                            rect = rect(x = 200f, y = 0f, w = 100f, h = 50f),
                        ),
                    ),
                lines =
                    listOf(
                        PortLabelAudit.LineEntry(
                            kind = ObstacleKind.EDGE,
                            ownerId = "conn:T::E1",
                            points = listOf(Point(x = 50f, y = 50f), Point(x = 50f, y = 500f)),
                        ),
                    ),
            )

        test("detector self test: a label that touches or hugs a part, another label or a foreign edge is counted") {
            // the connector E1 ends at T::a and T::far (owners, not foreign); T::x is the foreign part. Overlap: C2
            IbdLabelClearance.measure(synthetic(label = rect(x = 220f, y = 10f, w = 60f, h = 20f))).c2 shouldBe 1
            // 4 px beside the foreign part: C5 and no C2
            val near = IbdLabelClearance.measure(synthetic(label = rect(x = 304f, y = 10f, w = 40f, h = 20f)))
            near.c2 shouldBe 0
            near.c5 shouldBe 1
            // 10 px beside the foreign part: clean
            IbdLabelClearance.measure(synthetic(label = rect(x = 310f, y = 10f, w = 40f, h = 20f))).sum shouldBe 0
            // two labels on each other: C3 once
            val twin = synthetic(label = rect(x = 300f, y = 300f, w = 40f, h = 20f))
            val second =
                twin.labels.single().copy(
                    id = "conn:T::E2",
                    ownerId = "conn:T::E2",
                    rect = rect(x = 310f, y = 305f, w = 40f, h = 20f),
                )
            IbdLabelClearance.measure(twin.copy(labels = twin.labels + second)).c3 shouldBe 1
            // a foreign edge through the label: C4 (E2 is not the owner of the line)
            val crossed =
                twin.labels.single().copy(
                    id = "conn:T::E2",
                    ownerId = "conn:T::E2",
                    rect = rect(x = 30f, y = 200f, w = 60f, h = 20f),
                )
            IbdLabelClearance.measure(twin.copy(labels = listOf(crossed))).c4 shouldBe 1
        }

        test("C1 to C5 on the PAL IBD fixtures: all zero") {
            val rows = ArrayList<String>()
            for ((name, script) in fixtures()) {
                val (inner, snaps) =
                    PortLabelAudit.collecting(repair = true) {
                        NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) }
                    }
                inner.first.error shouldBe null
                // the last planning run that has labels is the final plan (Doc 41 plans twice because of its notes)
                val snap = snaps.last { it.labels.isNotEmpty() }
                val c1 = inner.second.count { it.code == PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION }
                val c = IbdLabelClearance.measure(snap)
                rows += "[welle-u] $name C1=$c1 C2=${c.c2} C3=${c.c3} C4=${c.c4} C5=${c.c5} labels=${snap.labels.size}"
                c1 shouldBe 0
                c.c2 shouldBe 0
                c.c3 shouldBe 0
                c.c4 shouldBe 0
                val c5Labels = c.details.filter { it.startsWith("C5 ") }.map { it.removePrefix("C5 ").substringBefore(" near part") }
                c5Labels shouldBe emptyList()
            }
            rows.forEach { println(it) }
        }
    })
