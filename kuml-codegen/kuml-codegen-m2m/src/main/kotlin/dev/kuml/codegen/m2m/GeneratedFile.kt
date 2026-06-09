package dev.kuml.codegen.m2m

/**
 * A single text file produced by a [KumlTransformer].
 *
 * @property relativePath Path relative to the output directory root.
 *   May contain subdirectories (e.g. `"com/example/User.kt"`).
 * @property content UTF-8 text content of the file.
 */
public data class GeneratedFile(
    val relativePath: String,
    val content: String,
)
