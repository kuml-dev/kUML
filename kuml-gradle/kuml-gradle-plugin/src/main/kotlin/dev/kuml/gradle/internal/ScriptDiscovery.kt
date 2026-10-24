package dev.kuml.gradle.internal

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files

/** A `*.kuml.kts` script found below a source directory. [relativePath] always uses `/`. */
internal data class DiscoveredScript(
    val relativePath: String,
    val file: File,
)

/** Finds `*.kuml.kts` scripts deterministically and safely. */
internal object ScriptDiscovery {
    const val DEFAULT_MAX_SCRIPTS: Int = 1000
    const val DEFAULT_MAX_SCRIPT_BYTES: Long = 1024L * 1024L

    /**
     * Returns the scripts below [sourceDir], sorted by relative path.
     *
     * Symbolic links are not followed during the walk, and a script whose real path leaves the
     * source directory is rejected. Count and size limits are checked before anything is evaluated.
     *
     * @throws GradleException for symlinks leaving the source directory or exceeded limits.
     */
    fun discover(
        sourceDir: File,
        maxScripts: Int = DEFAULT_MAX_SCRIPTS,
        maxScriptBytes: Long = DEFAULT_MAX_SCRIPT_BYTES,
    ): List<DiscoveredScript> {
        if (!sourceDir.isDirectory) return emptyList()
        val root = sourceDir.toPath().toRealPath()
        val found =
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kuml.kts") }
                    .map { path ->
                        val relative = root.relativize(path).joinToString("/") { it.toString() }
                        if (!path.toRealPath().startsWith(root)) {
                            throw GradleException("Script '$relative' resolves outside the source directory.")
                        }
                        DiscoveredScript(relativePath = relative, file = path.toFile())
                    }.toList()
            }
        val sorted = found.sortedBy { it.relativePath }
        if (sorted.size > maxScripts) {
            throw GradleException("Found ${sorted.size} scripts; the limit is $maxScripts.")
        }
        sorted.forEach { script ->
            val size = script.file.length()
            if (size > maxScriptBytes) {
                throw GradleException("Script '${script.relativePath}' is $size bytes; the limit is $maxScriptBytes.")
            }
        }
        return sorted
    }
}
