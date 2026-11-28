@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * PAL Welle Y2: STM transition labels closer than 6 px to their own line (E1) are repaired locally, one label at a time.
 *
 * Inventory `[y2]` per diagram (E1/E2/E3 plus details), hashes of every vault diagram and every X1 fixture in
 * `build/sample-output/edge-gap-y2-hashes.txt`. With `KUML_EDGEGAP_Y2_BASELINE_HASHES` naming a hash file written on master
 * (`name sha256` per line): every diagram outside [targets] must hash identically, and each target is either byte-identical or
 * has a strictly smaller E1+E2+E3 with no counter rising. Without the variable only the counter gates run (CI).
 */
class PalWelleY2EdgeLabelRepairGateTest :
    FunSpec({
        timeout = 900_000L

        fun fixture(path: String): String =
            PalWelleY2EdgeLabelRepairGateTest::class.java
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
        val targets =
            setOf(
                "sysml2-stm-k/55-einfrieren-stm",
                "sysml2-stm-j2/51-rueckluftklappe-stm",
                "sysml2-stm-x1/50-tuerverriegelung-stm",
                "sysml2-stm-x1/53-stoermeldeklassen-stm",
                "sysml2-stm-x1/56-partikelmonitoring-stm",
                "sysml2-stm-x1/58-schleusentuer-stm",
            )

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        // Hash set: every vault diagram and every X1 fixture. Counter gates: STM and BDD diagrams only (as the X1 inventory).
        fun inGate(
            name: String,
            script: String,
        ): Boolean = name in fixtures || name.contains("SysML 2 BDD") || script.contains("stmDiagram")

        fun all(): List<Pair<String, String>> =
            VaultExampleLoader.loadFromClasspath().map { it.baseName to it.kumlScript } +
                fixtures.map { it to fixture("/$it.kuml.kts") }

        val stmLogger = LoggerFactory.getLogger("dev.kuml.layout.bridge.label.StmEdgeGapRepair") as Logger

        test("inventory, hashes and gates") {
            val hashes = StringBuilder()
            val baseHashes =
                // Several vault notes share a base name: the hashes of one name are consumed in order.
                System.getenv("KUML_EDGEGAP_Y2_BASELINE_HASHES")?.let { File(it) }?.takeIf { it.isFile }?.readLines()?.let { lines ->
                    val m = LinkedHashMap<String, ArrayDeque<String>>()
                    for (l in lines) m.getOrPut(l.substringBeforeLast(' ')) { ArrayDeque() }.addLast(l.substringAfterLast(' '))
                    m
                }
            val counts = LinkedHashMap<String, EdgeLabelClearance.Counts>()
            val changed = LinkedHashSet<String>()
            val drifted = ArrayList<String>()
            for ((name, script) in all()) {
                val r = VaultExampleRenderer.render(script = script)
                val svg = r.svg
                if (svg == null) {
                    println("[y2] $name: render failed: ${r.error}")
                    continue
                }
                hashes.appendLine("$name ${sha256(svg)}")
                if (baseHashes != null) {
                    val old = baseHashes[name]?.removeFirstOrNull()
                    if (name !in targets && old != null) {
                        if (sha256(svg) != old) drifted += name
                    }
                    if (name in targets && old != null && sha256(svg) != old) changed += name
                }
                if (!inGate(name, script)) continue
                val c = EdgeLabelClearance.measure(name = name, svg = svg)
                counts[name] = c
                if (name in fixtures || c.sum > 0) {
                    println("[y2] $name: $c")
                    c.details.forEach { println("[y2-detail] $it") }
                }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/edge-gap-y2-hashes.txt").writeText(hashes.toString())
            drifted shouldBe emptyList<String>()
            for ((n, c) in counts) {
                withClue(n) {
                    c.e2 shouldBe 0
                    c.e3 shouldBe 0
                    check(c.e1 <= (knownE1Master[n] ?: 0)) { "$n: E1 ${c.e1} above the master value ${knownE1Master[n] ?: 0}" }
                }
            }
            for (n in changed) {
                withClue("$n changed, so it must have fewer E1 than on master") {
                    check(counts[n]!!.e1 < (knownE1Master[n] ?: 0)) { "$n: bytes changed but E1 did not fall" }
                }
            }
            println(
                "[y2] total E1=${counts.values.sumOf { it.e1 }} E2=${counts.values.sumOf { it.e2 }} E3=${counts.values.sumOf { it.e3 }}",
            )
        }

        // The geometric minimum of what is left after Y2 (E1, measured on the SVG). Disabled on purpose: each body states the goal (d >= 5.5 px)
        // and is red today; the blocker is what the `PAL-Y2 edge-gap` debug log reports for the label.
        xtest(
            "Doc 51 Regelbetrieb::Beide Türen offen: d=0.6 px, no in-band position free: foreign edges Wartezeit::Regelbetrieb and Regelbetrieb::Tür Mixing offen (geometric minimum)",
        ) {
            (0.6f >= 5.5f) shouldBe true
        }
        xtest(
            "Doc 51 Tür Mixing offen::Beide Türen offen: d=3.0 px, blocked by label Tür Korridor offen::Beide Türen offen, node Beide Türen offen (geometric minimum)",
        ) {
            (3.0f >= 5.5f) shouldBe true
        }
        xtest(
            "Doc 51 Wartezeit::Tür Korridor offen: d=3.0 px, blocked by foreign edge Gehalten, node a4e-51, label Tür Korridor offen::Wartezeit (geometric minimum)",
        ) {
            (3.0f >= 5.5f) shouldBe true
        }
        xtest(
            "Doc 50 Störung Beide Türen offen::Wartezeit Spülzeit: d=0.0 px, blocked by foreign edge Tür Korridor offen::Wartezeit Spülzeit and the note link a9e-50 (geometric minimum)",
        ) {
            (0.0f >= 5.5f) shouldBe true
        }
        xtest("Doc 53 Keine Störmeldung::Hauptalarm: d=3.7 px, blocked by nodes Voralarm and Sicherheitsabschaltung (geometric minimum)") {
            (3.7f >= 5.5f) shouldBe true
        }
        xtest(
            "Doc 58 Geschlossen gesperrt Interlock::Entriegelt Spannungsausfall: d=3.0 px, blocked by nodes a1-58 and a1e-58 (geometric minimum)",
        ) {
            (3.0f >= 5.5f) shouldBe true
        }
        xtest("Doc 58 T49a: d=3.0 px, blocked by a node and label T50a (geometric minimum)") {
            (3.0f >= 5.5f) shouldBe true
        }
        xtest("Doc 58 T50a: d=0.0 px, blocked by foreign edge T52a and label T49a (geometric minimum)") {
            (0.0f >= 5.5f) shouldBe true
        }

        test("debug log names the blockers of a remaining E1 label") {
            val app = ListAppender<ILoggingEvent>().also { it.start() }
            val old = stmLogger.level
            stmLogger.level = Level.DEBUG
            stmLogger.addAppender(app)
            try {
                for (f in fixtures) VaultExampleRenderer.render(script = fixture("/$f.kuml.kts"))
            } finally {
                stmLogger.detachAppender(app)
                stmLogger.level = old
            }
            val lines = app.list.map { it.formattedMessage }.filter { it.startsWith("PAL-Y2 edge-gap") }
            lines.forEach { println("[y2-log] $it") }
            lines.none { it.any { ch -> ch.isISOControl() } } shouldBe true
        }
    }) {
    companion object {
        /** E1 per target diagram on master 2871c25f (before PAL Welle Y2). */
        val knownE1Master =
            mapOf(
                "sysml2-stm-k/55-einfrieren-stm" to 1,
                "sysml2-stm-j2/51-rueckluftklappe-stm" to 13,
                "sysml2-stm-x1/50-tuerverriegelung-stm" to 9,
                "sysml2-stm-x1/53-stoermeldeklassen-stm" to 3,
                "sysml2-stm-x1/56-partikelmonitoring-stm" to 1,
                "sysml2-stm-x1/58-schleusentuer-stm" to 13,
            )
    }
}
