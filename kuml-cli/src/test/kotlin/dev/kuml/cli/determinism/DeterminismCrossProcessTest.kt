package dev.kuml.cli.determinism

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders all fixtures in two separate JVMs (the second with randomised identity hash
 * codes and a different default locale) and compares the SVG hashes. Fails instead of
 * silently skipping if the child process cannot run.
 */
class DeterminismCrossProcessTest :
    FunSpec({
        fun javaBin(): String = File(System.getProperty("java.home"), "bin/java").absolutePath

        fun supportsHashCodeFlag(): Boolean {
            val p =
                ProcessBuilder(javaBin(), "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2", "-version")
                    .redirectErrorStream(true)
                    .start()
            p.inputStream.readBytes()
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        }

        fun run(extra: List<String>): Map<String, String> {
            val cmd =
                listOf(javaBin()) + extra +
                    listOf("-cp", System.getProperty("java.class.path"), "dev.kuml.cli.determinism.DeterminismHarness")
            val proc =
                ProcessBuilder(cmd)
                    .directory(File(System.getProperty("user.dir")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                // Read on a helper thread so a hanging child cannot block the test beyond the timeout.
                val reader =
                    java.util.concurrent.CompletableFuture
                        .supplyAsync { proc.inputStream.bufferedReader().readText() }
                val text = reader.get(180, TimeUnit.SECONDS)
                proc.waitFor(60, TimeUnit.SECONDS) shouldBe true
                proc.exitValue() shouldBe 0
                return text
                    .lines()
                    .filter { it.contains('\t') }
                    .associate { it.substringBefore('\t') to it.substringAfter('\t') }
            } finally {
                proc.destroyForcibly()
            }
        }

        test("two JVMs with different hash seeds and locales render identical SVGs") {
            val a = run(listOf("-Duser.language=en", "-Duser.country=US"))
            a.entries.shouldNotBeEmpty()
            (a.values.count { it.startsWith("ERROR:") } * 4 < a.size) shouldBe true
            val variantFlags =
                if (supportsHashCodeFlag()) {
                    listOf("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2", "-Duser.language=de", "-Duser.country=DE")
                } else {
                    listOf("-Duser.language=de", "-Duser.country=DE")
                }
            val b = run(variantFlags)
            (b.values.count { it.startsWith("ERROR:") } * 4 < b.size) shouldBe true
            b.keys shouldBe a.keys
            val diffs = a.keys.filter { a[it] != b[it] }
            diffs shouldBe emptyList()
        }
    })
