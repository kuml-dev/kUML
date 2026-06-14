package dev.kuml.codegen.reverse.registry

import dev.kuml.codegen.reverse.KumlReverseEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

/** ServiceLoader wrapper that exposes all registered reverse engines. */
public object ReverseEngineRegistry {
    /** All currently visible engines (classpath snapshot at call time). */
    public fun all(): List<KumlReverseEngine> = ServiceLoader.load(KumlReverseEngine::class.java).toList()

    /** Find an engine by id. Returns null when not registered. */
    public fun byId(id: String): KumlReverseEngine? = all().firstOrNull { it.id == id }

    /**
     * Heuristic: detects the language by inspecting the file extensions present
     * under the given source roots. Returns the engine id if a clear majority is
     * found, otherwise null (ambiguous or empty). Used by V3.0.9 CLI `--lang auto`.
     */
    public fun detectLanguage(sourceRoots: List<Path>): String? {
        val counts = mutableMapOf<String, Int>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val name = file.fileName.toString()
                    when {
                        name.endsWith(".java") -> counts["java"] = (counts["java"] ?: 0) + 1
                        name.endsWith(".kt") -> counts["kotlin"] = (counts["kotlin"] ?: 0) + 1
                    }
                }
            }
        }
        if (counts.isEmpty()) return null
        val dominant = counts.maxByOrNull { it.value } ?: return null
        // Only return a language if its share is at least 60% of total files scanned
        val total = counts.values.sum()
        return if (dominant.value.toDouble() / total >= 0.6) dominant.key else null
    }
}
