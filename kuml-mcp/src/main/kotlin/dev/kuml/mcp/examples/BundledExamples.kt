package dev.kuml.mcp.examples

import java.io.File
import java.util.jar.JarFile

/**
 * Classpath access to the curated vault-example Markdown notes bundled as `*.md` files
 * under the `dsl/examples` directory by `:kuml-mcp:processResources` (see
 * `kuml-mcp/build.gradle.kts`).
 *
 * Extracted from the resource-listing logic that used to live privately in
 * `ResourceRegistry` (V3.2.17) so [ExampleCatalog], the aggregate `kuml://dsl/examples`
 * resource, and the granular per-type resources/tool all share one implementation.
 */
internal object BundledExamples {
    private const val RESOURCE_DIR = "/dsl/examples"

    // First ```kuml fenced block only — mirrors VaultExampleLoader.FENCE_REGEX in
    // kuml-vault-examples-tests, but intentionally takes only the first match: curated
    // catalog entries are single-script, and some notes (e.g. the BPMN→UML-activity
    // bridge note) carry a second, non-curated ```kuml block that must be ignored.
    private val FENCE_REGEX = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

    /**
     * Lists every bundled `*.md` name (e.g. "01 UML Klasse – Order Domain.md") on the
     * classpath under [RESOURCE_DIR]. Handles both the `file:` protocol (tests, running
     * from `build/resources/`) and the `jar:` protocol (the packaged `kuml-mcp` binary).
     */
    internal fun listNames(): List<String> {
        val dirUrl = javaClass.getResource("$RESOURCE_DIR/") ?: return emptyList()
        return when (dirUrl.protocol) {
            "file" -> {
                val dir = File(dirUrl.toURI())
                dir
                    .listFiles { file -> file.isFile && file.name.endsWith(".md") }
                    ?.map { it.name }
                    ?: emptyList()
            }
            "jar" -> {
                val jarPath = dirUrl.path.substringBefore("!").removePrefix("file:")
                JarFile(jarPath).use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith("dsl/examples/") && it.name.endsWith(".md") }
                        .map { it.name.removePrefix("dsl/examples/") }
                        .toList()
                }
            }
            else -> emptyList()
        }
    }

    /** Raw markdown of one bundled note, or null if absent on the classpath. */
    internal fun readRaw(fileName: String): String? {
        // Defense in depth: callers only ever pass catalog constants or names enumerated
        // by listNames(), but reject path-like names outright so a future refactor cannot
        // silently route client input into a classpath traversal.
        require(!fileName.contains('/') && !fileName.contains('\\') && !fileName.contains("..")) {
            "Invalid bundled example file name: '$fileName'"
        }
        return javaClass
            .getResourceAsStream("$RESOURCE_DIR/$fileName")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
    }

    /** First ` ```kuml ` fenced block of [markdown], trimmed; null if none present. */
    internal fun extractKumlBlock(markdown: String): String? =
        FENCE_REGEX
            .find(markdown)
            ?.groupValues
            ?.get(1)
            ?.trim()
}
