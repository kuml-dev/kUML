package dev.kuml.cli.determinism

import dev.kuml.cli.RenderPipeline
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * PAL 2B determinism harness: renders every fixture to SVG through [RenderPipeline] and
 * prints one `name<TAB>sha256` line per fixture. Fixtures are the ```kuml blocks of the
 * vault examples (committed classpath copies), so every diagram family is covered.
 *
 * Used in-process by `DeterminismSameJvmTest` and as a separate JVM by
 * `DeterminismCrossProcessTest`. Never compares against checked-in references.
 */
object DeterminismHarness {
    /** Directory with the mirrored vault examples, relative to the kuml-cli module. */
    val EXAMPLES_DIR: File = File("../kuml-tests/kuml-vault-examples-tests/src/test/resources/vault-examples")

    private val KUML_BLOCK = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

    /** Fixtures that are skipped because they are not plain single-diagram SVG renders. */
    private fun isExcluded(name: String): Boolean = name.startsWith("00 ") || name.contains("animiert")

    fun fixtures(): List<Pair<String, String>> {
        val dir = EXAMPLES_DIR
        check(dir.isDirectory) { "Example fixtures directory not found: ${dir.absolutePath}" }
        return dir
            .listFiles { f -> f.name.endsWith(".md") && !isExcluded(f.name) }!!
            .sortedBy { it.name }
            .mapNotNull { f ->
                KUML_BLOCK
                    .find(f.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.let { f.nameWithoutExtension to it }
            }
    }

    fun renderHash(
        name: String,
        source: String,
    ): String {
        val tmp = Files.createTempDirectory("kuml-determinism")
        try {
            val script = tmp.resolve("fixture.kuml.kts").toFile().apply { writeText(source) }
            val out = tmp.resolve("fixture.svg")
            return try {
                RenderPipeline.run(input = script, output = out, format = "svg", width = 1024, themeName = "kuml")
                sha256(Files.readAllBytes(out))
            } catch (e: Exception) {
                // A deterministic failure is still a stable result.
                "ERROR:" + e.javaClass.simpleName
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun renderAll(): List<Pair<String, String>> = fixtures().map { (name, src) -> name to renderHash(name = name, source = src) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        renderAll().forEach { (name, hash) -> println("$name\t$hash") }
    }
}
