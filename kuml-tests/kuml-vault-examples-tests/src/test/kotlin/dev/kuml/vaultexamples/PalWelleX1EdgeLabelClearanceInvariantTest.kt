@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL Welle X1: edge labels of STM and BDD diagrams keep 6 to 16 px from their own edge, and multiplicities never overlap
 * an arrowhead (E1/E2/E3, see [EdgeLabelClearance]). The inventory prints one line per diagram (`[x1]`).
 */
class PalWelleX1EdgeLabelClearanceInvariantTest :
    FunSpec({
        timeout = 600_000L

        fun fixture(path: String): String =
            PalWelleX1EdgeLabelClearanceInvariantTest::class.java
                .getResourceAsStream(path)!!
                .bufferedReader()
                .readText()

        val fixtures =
            listOf(
                "sysml2-stm-k/55-einfrieren-stm",
                "sysml2-stm-j2/51-rueckluftklappe-stm",
                "sysml2-stm-x1/50-tuerverriegelung-stm",
                "sysml2-stm-x1/52-druckampel-stm",
                "sysml2-stm-x1/53-stoermeldeklassen-stm",
                "sysml2-stm-x1/54-erhitzer-stm",
                "sysml2-stm-x1/56-partikelmonitoring-stm",
                "sysml2-stm-x1/57-betriebsart-vitec-stm",
                "sysml2-stm-x1/58-schleusentuer-stm",
                "sysml2-bdd-x1/31-sensorik-sicherheit-bdd",
                "sysml2-bdd-x1/34-funktionsallokation-bdd",
            )

        fun diagrams(): List<Pair<String, String>> {
            val vault =
                VaultExampleLoader
                    .loadFromClasspath()
                    .filter { it.baseName.contains("SysML 2 BDD") || it.kumlScript.contains("stmDiagram") }
                    .map { it.baseName to it.kumlScript }
            return vault + fixtures.map { it to fixture("/$it.kuml.kts") }
        }

        fun measureAll(): Map<String, EdgeLabelClearance.Counts> {
            val out = LinkedHashMap<String, EdgeLabelClearance.Counts>()
            for ((name, script) in diagrams()) {
                val r = VaultExampleRenderer.render(script = script)
                if (r.error != null || r.svg == null) continue
                out[name] = EdgeLabelClearance.measure(name = name, svg = r.svg!!)
            }
            return out
        }

        test("inventory of E1/E2/E3 over STM and BDD diagrams") {
            val all = measureAll()
            all.forEach { (n, c) -> println("[x1] $n: $c") }
            all.values.flatMap { it.details }.forEach { println("[x1-detail] $it") }
            println("[x1] total: E1=${all.values.sumOf { it.e1 }} E2=${all.values.sumOf { it.e2 }} E3=${all.values.sumOf { it.e3 }}")
            (all.size > 0) shouldBe true
        }

        // E1 before PAL Welle X1 (nearest label offset 3 px STM / 4 px BDD, multiplicities on the arrowhead): 104, E3: 4, E2: 0.
        // Now: the diagrams below keep a known remainder (the gate keeps the original plan of a crowded diagram: a label has no free spot or the notes sit against the
        // original labels); every other diagram is at 0. E2 and E3 are 0 everywhere.
        val knownE1 =
            mapOf(
                "sysml2-stm-k/55-einfrieren-stm" to 1,
                "sysml2-stm-j2/51-rueckluftklappe-stm" to 13,
                "sysml2-stm-x1/50-tuerverriegelung-stm" to 9,
                "sysml2-stm-x1/53-stoermeldeklassen-stm" to 3,
                "sysml2-stm-x1/56-partikelmonitoring-stm" to 1,
                "sysml2-stm-x1/58-schleusentuer-stm" to 13,
            )

        test("edge labels keep 6 to 16 px from their own edge, multiplicities clear the arrowheads") {
            val all = measureAll()
            all.size shouldBe diagrams().size
            for ((n, c) in all) {
                withClue(n) {
                    c.e2 shouldBe 0
                    c.e3 shouldBe 0
                    c.e1 shouldBe (knownE1[n] ?: 0)
                }
            }
        }
    })
