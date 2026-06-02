package dev.kuml.cli

import java.io.File
import java.nio.file.Path

/**
 * Resolves the output format and default output path for the render command.
 *
 * Format resolution priority:
 * 1. `--format` flag, if set
 * 2. Suffix of `--output` path, if it ends with `.svg` or `.png`
 * 3. Fallback: `"svg"`
 */
internal object FormatResolver {
    /**
     * Resolves the output format from the given options.
     *
     * @param format Explicit `--format` option value, or null if not set.
     * @param output Explicit `--output` path, or null if not set.
     * @param input The input script file (used as fallback context).
     * @return The resolved format string, either `"svg"` or `"png"`.
     */
    internal fun resolve(
        format: String?,
        output: Path?,
        @Suppress("UNUSED_PARAMETER") input: File,
    ): String {
        if (format != null) return format
        if (output != null) {
            val name = output.fileName?.toString() ?: ""
            if (name.endsWith(".svg")) return "svg"
            if (name.endsWith(".png")) return "png"
        }
        return "svg"
    }

    /**
     * Computes the default output path when `--output` is not set.
     *
     * Strips `.kuml.kts` or `.kts` from the input filename and appends the format suffix.
     *
     * Examples:
     * - `order.kuml.kts` + `svg` → `order.svg`
     * - `order.kts` + `png` → `order.png`
     *
     * @param input The input script file.
     * @param format The resolved output format (`"svg"` or `"png"`).
     * @return The default output [Path] adjacent to the input file.
     */
    internal fun defaultOutput(
        input: File,
        format: String,
    ): Path {
        val base =
            input.name
                .removeSuffix(".kuml.kts")
                .removeSuffix(".kts")
        val parent = input.parentFile ?: File(".")
        return parent.resolve("$base.$format").toPath()
    }
}
