package dev.kuml.vaultexamples

data class VaultExample(
    /** Original filename from the vault, e.g. "01 UML Klasse – Order Domain.md". */
    val fileName: String,
    /** Filename without `.md` extension. */
    val baseName: String,
    /** Filesystem-safe variant suitable for output filenames. */
    val sanitizedName: String,
    /** The contents of the ` ```kuml ` fenced code block. */
    val kumlScript: String,
)

object VaultExampleLoader {
    private val FENCE_REGEX = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

    /** Classpath base directory under src/test/resources/ where vault .md files live. */
    private const val RESOURCE_DIR = "/vault-examples"

    /** Filename listing — also lives in the resources so it travels with the JAR. */
    private const val INDEX_RESOURCE = "$RESOURCE_DIR/_files.txt"

    /**
     * Loads every vault example from the classpath resource directory.
     *
     * Strategy:
     *  1. Try to load the manifest file `_files.txt` (one filename per line).
     *  2. If absent, fall back to filesystem listing — used in dev mode when
     *     running from the IDE before the sync script has produced a manifest.
     *
     * This indirection is necessary because Java's `ClassLoader` cannot list
     * classpath resources in a directory at runtime; the manifest is the
     * portable workaround that also works when resources come from a JAR.
     */
    fun loadFromClasspath(): List<VaultExample> {
        val names = readManifest() ?: discoverFromFilesystem()
        return names
            .sorted()
            .flatMap { name -> readResource(name)?.let { extractFromMarkdown(name, it) } ?: emptyList() }
    }

    private fun readManifest(): List<String>? {
        val stream = VaultExampleLoader::class.java.getResourceAsStream(INDEX_RESOURCE) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    }

    /**
     * Fallback for IDE / direct-test runs where the resource directory exists
     * on disk but the manifest has not been generated yet.
     */
    private fun discoverFromFilesystem(): List<String> {
        val url = VaultExampleLoader::class.java.getResource(RESOURCE_DIR) ?: return emptyList()
        if (url.protocol != "file") return emptyList()
        val dir = java.io.File(url.toURI())
        return (dir.listFiles() ?: emptyArray<java.io.File>())
            .filter { it.extension == "md" }
            .map { it.name }
    }

    private fun readResource(fileName: String): String? =
        VaultExampleLoader::class.java
            .getResourceAsStream("$RESOURCE_DIR/$fileName")
            ?.bufferedReader(Charsets.UTF_8)
            ?.readText()

    private fun extractFromMarkdown(
        fileName: String,
        content: String,
    ): List<VaultExample> {
        val matches = FENCE_REGEX.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val base = fileName.removeSuffix(".md")
        val sanitized = sanitize(base)
        return matches.mapIndexed { idx, m ->
            val script = m.groupValues[1]
            val suffix = if (matches.size > 1) "-$idx" else ""
            VaultExample(
                fileName = fileName,
                baseName = base,
                sanitizedName = "$sanitized$suffix",
                kumlScript = script,
            )
        }
    }

    private fun sanitize(name: String): String =
        name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
}
