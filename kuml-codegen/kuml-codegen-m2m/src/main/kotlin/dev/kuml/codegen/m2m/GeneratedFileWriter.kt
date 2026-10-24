package dev.kuml.codegen.m2m

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Writes [GeneratedFile]s below an output directory, shared by the CLI and the Gradle plugin.
 *
 * Guards against path traversal: absolute paths and paths that leave the output directory
 * (for example `../x`) are rejected before anything is written. Files are written in sorted
 * `relativePath` order so that repeated runs behave identically.
 */
public object GeneratedFileWriter {
    /**
     * Checks that a transformer's raw [output] really is a list of [GeneratedFile]s.
     *
     * The registry lookup `TransformerRegistry.get<S, T>(id)` is an unchecked cast; transformers such
     * as `uml-to-exposed-psm` or `erm-to-exposed` produce other output types. Callers pass the output
     * as `Any?` (so the compiler inserts no cast) and get a clear error instead of a
     * [ClassCastException].
     *
     * @throws IllegalArgumentException when [output] is not a `List<GeneratedFile>`.
     */
    public fun requireFiles(
        output: Any?,
        transformerId: String,
    ): List<GeneratedFile> {
        require(output is List<*> && output.all { it is GeneratedFile }) {
            "Transformer '$transformerId' does not produce files " +
                "(output is ${output?.let { it::class.simpleName } ?: "null"}); choose a file-producing transformer."
        }
        @Suppress("UNCHECKED_CAST")
        return output as List<GeneratedFile>
    }

    /**
     * Writes [files] below [outputDir] (created when missing) and returns the written files.
     *
     * @param rejectDuplicates when `true`, a repeated `relativePath` is an error (typical symptom of
     *   colliding declarations when several models were merged). When `false`, the last entry wins.
     * @throws IllegalArgumentException for absolute or escaping paths, or rejected duplicates.
     */
    public fun write(
        files: List<GeneratedFile>,
        outputDir: File,
        rejectDuplicates: Boolean,
    ): List<File> {
        val root: Path = outputDir.toPath().toAbsolutePath().normalize()

        if (rejectDuplicates) {
            val duplicate =
                files
                    .groupingBy { it.relativePath }
                    .eachCount()
                    .entries
                    .firstOrNull { it.value > 1 }
            require(duplicate == null) {
                "Generated file '${duplicate?.key}' was produced ${duplicate?.value} times; " +
                    "colliding declarations in the model?"
            }
        }

        val targets = LinkedHashMap<String, Pair<Path, GeneratedFile>>()
        for (file in files.sortedBy { it.relativePath }) {
            val relative = Paths.get(file.relativePath)
            require(!relative.isAbsolute && !file.relativePath.startsWith("/") && !file.relativePath.startsWith("\\")) {
                "Generated file path '${file.relativePath}' must be relative."
            }
            val target = root.resolve(relative).normalize()
            require(target.startsWith(root) && target != root) {
                "Generated file path '${file.relativePath}' escapes the output directory."
            }
            targets[file.relativePath] = target to file
        }

        outputDir.mkdirs()
        return targets.values.map { (target, file) ->
            val out = target.toFile()
            out.parentFile?.mkdirs()
            out.writeText(file.content)
            out
        }
    }
}
