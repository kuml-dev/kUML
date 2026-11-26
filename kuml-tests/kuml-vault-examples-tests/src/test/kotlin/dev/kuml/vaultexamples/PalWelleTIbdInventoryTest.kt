package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.IbdLaneSpreadProbe
import dev.kuml.layout.bridge.label.PortLabelAudit
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL Welle T inventory: every IBD diagram of the vault examples and the fixtures is rendered with the lane spread switched
 * off and on, in the same JVM (so SVG strings may be compared). The spread may change a diagram only where connectors really
 * ran side by side before (at least one parallel violation measured by the spread itself); a changed diagram must have
 * strictly fewer violations afterwards, and the render must not report more `KUML-IBD-W002` than without the spread. Every
 * other diagram stays byte-identical, and so do its port label rectangles. Changed diagrams are printed (`[welle-t-inv]`).
 */
class PalWelleTIbdInventoryTest :
    FunSpec({
        timeout = 900_000L

        val palDocs =
            listOf(
                "pal-ibd/41-druckregelung-ibd",
                "pal-ibd/42-erhitzer-ibd",
                "pal-ibd/46-erhitzer-sicherheit-ibd",
                "pal-ibd/47-erhitzer-stb-ibd",
            )
        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

        fun resource(path: String) =
            PalWelleTIbdInventoryTest::class.java
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

        class Measured(
            val svg: String?,
            val before: Int,
            val after: Int,
            val portRects: Map<String, dev.kuml.layout.Rect>,
            val w002: Boolean,
        )

        fun measure(
            script: String,
            spread: Boolean,
        ): Measured {
            val (inner, snaps) =
                IbdLaneSpreadProbe.collecting(spread = spread) {
                    PortLabelAudit.collecting(
                        repair = true,
                    ) { NotePlacementMissCollector.collecting { VaultExampleRenderer.render(script = script) } }
                }
            val (rendered, labelSnaps) = inner
            val last = labelSnaps.lastOrNull { it.ports.isNotEmpty() }
            return Measured(
                svg = rendered.first.svg,
                before = snaps.sumOf { it.before },
                after = snaps.sumOf { it.after },
                portRects = last?.ports?.associate { it.id to it.rect } ?: emptyMap(),
                w002 = rendered.second.any { it.code == PlacementHintMiss.PORT_LABEL_NO_FREE_POSITION },
            )
        }

        test("the lane spread changes only diagrams that had side by side connectors, and makes none worse") {
            val changed = ArrayList<String>()
            var checked = 0
            for ((name, script) in scripts()) {
                val off = measure(script = script, spread = false)
                if (off.svg == null) continue
                val on = measure(script = script, spread = true)
                checked++
                val differs = off.svg != on.svg
                println("[welle-t-inv] $name violations ${off.before} -> ${on.after} changed=$differs")
                if (differs) {
                    changed += name
                    (on.before >= 1) shouldBe true
                    (on.after < on.before) shouldBe true
                    // a moved connector must not push a port label into trouble
                    (on.w002 && !off.w002) shouldBe false
                } else {
                    // unchanged: identical bytes and identical port label rectangles
                    on.portRects shouldBe off.portRects
                }
            }
            println("[welle-t-inv] IBD diagrams checked: $checked, changed: $changed")
            (checked >= 5) shouldBe true
            // measured inventory (2026-11-26): Doc 42 is the only IBD with side by side connectors; any other change is a surprise
            changed shouldBe listOf("pal-ibd/42-erhitzer-ibd")
        }

        test("W014 inventory: the sameLayer groups that are not honoured in the vault examples and the fixtures") {
            val found = ArrayList<String>()
            val all = VaultExampleLoader.loadFromClasspath().map { "vault/${it.baseName}" to it.kumlScript }
            for ((name, script) in all + scripts()) {
                val (_, findings) = VaultExampleRenderer.collectingFindings { VaultExampleRenderer.render(script = script) }
                for (f in findings.filter { it.code == "KUML-LAYOUT-W014" }.distinct()) {
                    found += "$name: ${f.message}"
                    println("[welle-t-w014] $name: ${f.message}")
                }
            }
            println("[welle-t-w014] total: ${found.size}")
            found shouldBe emptyList<String>()
        }

        test("W014 positive control: a sameLayer over two connected parts of Doc 42 is reported, naming the connector") {
            val source = resource("/pal-ibd/42-erhitzer-ibd.kuml.kts")
            val withGroup =
                source.replace(
                    "last(\"${'$'}T::zuluftkanal\")",
                    "sameLayer(\"${'$'}T::fmcs\", \"${'$'}T::erhitzer\")\n            last(\"${'$'}T::zuluftkanal\")",
                )
            (withGroup != source) shouldBe true
            val (_, findings) = VaultExampleRenderer.collectingFindings { VaultExampleRenderer.render(script = withGroup) }
            val w014 = findings.filter { it.code == "KUML-LAYOUT-W014" }.distinct()
            println("[welle-t-w014] control: ${w014.map { it.message }}")
            w014.size shouldBe 1
            (w014.single().message.contains("I13")) shouldBe true
        }
    })
