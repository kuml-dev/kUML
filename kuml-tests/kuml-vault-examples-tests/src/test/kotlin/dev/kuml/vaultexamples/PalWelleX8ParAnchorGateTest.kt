@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * PAL Welle X8 measuring gate: anchors of parametric bindings at the node frame, see [ParAnchorMetrics]. Inputs: the vault
 * examples 28, 51 and 56, the pressure cascade script of [PalE2ParBindingLabelGapTest] and, when present locally, all fenced
 * blocks of PAL document 80 (read only, copied into a temp dir; skipped on CI). Measuring output goes to
 * `build/sample-output/par-anchor.txt`.
 */
class PalWelleX8ParAnchorGateTest :
    FunSpec({
        timeout = 900_000L

        val fenced = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val palDir = File("/Users/irakli/Documents/kUML/Workspaces/personalschleuse-pal-bereich-mixing-asp-81/models")
        val vaultPrefixes = listOf("28 ", "51 ", "56 ")
        val onCi = System.getenv("CI") != null

        fun palScripts(): List<Pair<String, String>> {
            if (onCi || !palDir.isDirectory) {
                println("[welle-x8] PAL workspace not used (CI or missing), PAL document 80 skipped")
                return emptyList()
            }
            val tmp =
                java.nio.file.Files
                    .createTempDirectory("pal-x8")
                    .toFile()
            return try {
                val src = File(palDir, "80-parametrik-par.md")
                if (!src.isFile) return emptyList()
                val copy = File(tmp, src.name).also { src.copyTo(it, overwrite = true) }
                fenced.findAll(copy.readText()).mapIndexed { i, m -> "pal/80-parametrik-par#$i" to m.groupValues[1] }.toList()
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
            return vault + listOf("pal-e2/druckkaskade" to PalE2ParBindingLabelGapTest.SCRIPT) + palScripts()
        }

        // Baseline measured on master 96b06b9e (PAL wave X7), P1 = P2 = 0 everywhere, P3 = a label 4.0 px beside its own line:
        // vault 28: 3, vault 51: 5, vault 56: 0, pal-e2/druckkaskade: 15, PAL 80 block 0: 15. After the wave every input is 0.
        val baselineP3: Map<String, Int> =
            mapOf(
                "vault/28 SysML 2 PAR – Newton" to 3,
                "vault/51 SysML 2 PAR – Verschachtelte Constraints" to 5,
                "pal-e2/druckkaskade" to 15,
            )

        test("measure the parametric anchors") {
            val out = StringBuilder()
            for ((name, script) in scripts()) {
                val svg = VaultExampleRenderer.render(script = script).svg
                if (svg == null) {
                    println("[welle-x8] $name: render failed")
                    continue
                }
                SampleOutput.write(filename = "pal-x8/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".svg", content = svg)
                val rep = ParAnchorMetrics.measure(svg)
                val line = "$name  $rep  ${rep.details()}"
                println("[welle-x8] $line")
                out.appendLine(line)
                check(rep.p1.isEmpty()) { "$name: P1 ${rep.p1.size}: ${rep.p1}" }
                check(rep.p2.isEmpty()) { "$name: P2 ${rep.p2.size}: ${rep.p2}" }
                check(rep.p3.isEmpty()) { "$name: P3 ${rep.p3.size} (baseline ${baselineP3[name] ?: 0}): ${rep.p3}" }
            }
            File("build/sample-output").also { it.mkdirs() }
            File("build/sample-output/par-anchor.txt").writeText(out.toString())
        }
    })
