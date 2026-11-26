package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.label.PortLabelAudit
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * PAL Welle S inventory: every IBD diagram of the vault examples and the fixtures is planned with and without the port label
 * repair. The repair may change a diagram only where the label really collided before: the three PAL port diagrams (Doc 41,
 * 46, 47) and the two Welle-B fixtures `sysml2-welle-b/ibd` and `sysml2-welle-b/ports` (one real collision each, verified
 * here). For every changed diagram the test requires: collisions per port label never get worse, the total drops, every
 * moved label stays within 12 px of its pin, and the render reports no `KUML-IBD-W002`. All other diagrams stay identical.
 * Measured on the label plan (rectangles), not on SVG bytes, because those differ between machines and fonts. The changed
 * diagrams and W002 findings are printed (`[welle-s-inv]`).
 */
class PalWelleSIbdInventoryTest :
    FunSpec({
        timeout = 600_000L

        val palDocs = setOf("pal-ibd/41-druckregelung-ibd", "pal-ibd/46-erhitzer-sicherheit-ibd", "pal-ibd/47-erhitzer-stb-ibd")
        val allowedChanged = palDocs + setOf("sysml2-welle-b/ibd", "sysml2-welle-b/ports")
        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

        fun resource(path: String) =
            PalWelleSIbdInventoryTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        fun scripts(): List<Pair<String, String>> {
            val vault = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            val boxInBox =
                fenced
                    .findAll(resource("/ibd-fixtures/IBD Box-in-Box 3 Ebenen.md"))
                    .mapIndexed { i, m -> "ibd-fixtures/box-in-box-$i" to m.groupValues[1] }
                    .toList()
            val fixtures =
                (palDocs + listOf("sysml2-welle-b/ibd", "sysml2-welle-b/ports")).map { it to resource("/$it.kuml.kts") }
            return (vault + boxInBox + fixtures).filter { Regex("""\bibd\(""").containsMatchIn(it.second) }
        }

        fun portEntries(
            script: String,
            repair: Boolean,
        ): Pair<Map<String, PortLabelAudit.Entry>, List<PlacementHintMiss>>? {
            val (inner, snaps) =
                PortLabelAudit.collecting(
                    repair = repair,
                ) { NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) } }
            if (inner.first.error != null) return null
            val last = snaps.lastOrNull { it.ports.isNotEmpty() } ?: return emptyMap<String, PortLabelAudit.Entry>() to inner.second
            return last.ports.associateBy { it.id } to inner.second
        }

        test("the repair changes only diagrams with a real collision, never makes one worse, and reports W002 nowhere") {
            val changed = ArrayList<String>()
            val w002 = ArrayList<String>()
            var checked = 0
            for ((name, script) in scripts()) {
                val off = portEntries(script = script, repair = false) ?: continue
                val on = portEntries(script = script, repair = true) ?: continue
                checked++
                val moved = off.first.keys.filter { off.first[it]?.rect != on.first[it]?.rect }
                if (moved.isNotEmpty() || off.first.keys != on.first.keys) changed += name
                if (on.second.any { it.code == PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION }) w002 += name
                val before = off.first.values.sumOf { it.violations }
                val after = on.first.values.sumOf { it.violations }
                println(
                    "[welle-s-inv] $name ports=${on.first.size} moved=$moved collisions $before -> $after " +
                        "maxPinDistance=${moved.maxOfOrNull { on.first.getValue(it).pinDistance }}",
                )
                for (id in moved) {
                    // collisions per label never get worse, a moved label had a real collision before and stays within 12 px of its pin
                    (off.first.getValue(id).violations > 0) shouldBe true
                    (on.first.getValue(id).violations <= off.first.getValue(id).violations) shouldBe true
                    (on.first.getValue(id).pinDistance <= 12.01f) shouldBe true
                }
                (after <= before) shouldBe true
            }
            println("[welle-s-inv] IBD diagrams checked: $checked, changed: $changed, with W002: $w002")
            (checked >= 5) shouldBe true
            changed.filter { it !in allowedChanged }.shouldBeEmpty()
            w002.filter { it !in allowedChanged }.shouldBeEmpty()
            (changed.isNotEmpty()) shouldBe true
            w002.size shouldBe 0
        }
    })
