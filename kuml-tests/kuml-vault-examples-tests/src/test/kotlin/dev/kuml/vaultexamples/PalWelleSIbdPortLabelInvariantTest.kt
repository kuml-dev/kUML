package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.label.LabelPlacer
import dev.kuml.layout.bridge.label.PortLabelAudit
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * PAL Welle S: in the three real PAL port diagrams (Doc 41 Druckregelung, Doc 46 and Doc 47 Erhitzer; classpath fixtures
 * the `pal-ibd` resource folder, copied unchanged from the PAL workspace) every port label keeps clear of every part, pin, title, other
 * label, edge and of the border of its own part, and stands no further than 12 px from its pin.
 *
 * Measured on the label plan the production renderer builds ([PortLabelAudit], rectangles from the font independent plan,
 * no SVG bytes and no fonts): the audit counts every collision of a finished plan, also with labels that were placed after
 * the port label (edge labels) and with the note anchor lines of the second planning pass.
 *
 * Before the repair (`repair = false`) Doc 41 has two collisions: the label `stellsignal` of the FMCS touches the part
 * `Schleusenraum`, and the label `einfrieren` of the FMCS sits on the anchor line of note `a5e-41`. Doc 46 and Doc 47 had none by
 * this measure. After the repair all three have none, and the render reports no `KUML-IBD-W002`.
 */
class PalWelleSIbdPortLabelInvariantTest :
    FunSpec({
        timeout = 300_000L
        val docs = listOf("41-druckregelung-ibd", "46-erhitzer-sicherheit-ibd", "47-erhitzer-stb-ibd")
        // 12 px until Welle V, which reaches 24 px for the 6 px part clearance
        val maxPinDistance = LabelPlacer.PART_CLEAR_MAX_DISTANCE_PX + 0.01f

        fun script(doc: String) =
            PalWelleSIbdPortLabelInvariantTest::class.java
                .getResourceAsStream("/pal-ibd/$doc.kuml.kts")!!
                .bufferedReader()
                .readText()

        class Measured(
            val snapshot: PortLabelAudit.Snapshot,
            val misses: List<PlacementHintMiss>,
        )

        fun measure(
            doc: String,
            repair: Boolean,
        ): Measured {
            val (inner, snaps) =
                PortLabelAudit.collecting(repair = repair) {
                    NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script(doc)) }
                }
            inner.first.error shouldBe null
            // The last run with port labels is the final plan (the second pass runs when the diagram has notes).
            return Measured(snapshot = snaps.last { it.ports.isNotEmpty() }, misses = inner.second)
        }

        test("a) before the repair Doc 41 has collisions: stellsignal touches Schleusenraum, einfrieren sits on a note anchor line") {
            val m = measure(doc = "41-druckregelung-ibd", repair = false)
            val byId = m.snapshot.ports.associateBy { it.id }
            val stell = byId.getValue("port:Teilsystem Druckregelung::fmcs::stellsignal")
            (stell.violations >= 1) shouldBe true
            stell.blockers shouldContain "node Teilsystem Druckregelung::schleusenraum"
            val frei = byId.getValue("port:Teilsystem Druckregelung::fmcs::einfrieren")
            (frei.violations >= 1) shouldBe true
            println("[welle-s] before: ${m.snapshot.ports.filter { it.violations > 0 }.map { it.id to it.blockers }}")
        }

        for (doc in docs) {
            test("b) after the repair $doc: no port label collides with anything and none stands more than 24 px from its pin") {
                val before = measure(doc = doc, repair = false)
                val after = measure(doc = doc, repair = true)
                val bad = after.snapshot.ports.filter { it.violations > 0 }
                println(
                    "[welle-s] $doc ports=${after.snapshot.ports.size} before=${before.snapshot.ports.sumOf { it.violations }} " +
                        "after=${after.snapshot.ports.sumOf { it.violations }} otherLabelCollisions=" +
                        "${before.snapshot.otherLabelViolations}->${after.snapshot.otherLabelViolations}",
                )
                bad.map { it.id to it.blockers } shouldBe emptyList()
                // d) the farthest label is 12 px from its pin at most (rectangle distance)
                after.snapshot.ports
                    .filter { it.pinDistance > maxPinDistance }
                    .map { it.id } shouldBe emptyList()
                // c) the other labels (edge, multiplicity, role) are not worse off
                (after.snapshot.otherLabelViolations <= before.snapshot.otherLabelViolations) shouldBe true
                after.snapshot.labelCount shouldBe before.snapshot.labelCount
                after.misses.map { it.code } shouldBe
                    after.misses.map { it.code }.filter { it != PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION }
            }
        }

        test("the repair never makes a port label worse than the ranked position in these diagrams") {
            for (doc in docs) {
                val before = measure(doc = doc, repair = false).snapshot.ports.associateBy { it.id }
                val after = measure(doc = doc, repair = true).snapshot.ports
                for (p in after) (p.violations <= before.getValue(p.id).violations) shouldBe true
            }
        }
    })
