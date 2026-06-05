package dev.kuml.codegen.api

import dev.kuml.core.model.KumlDiagram
import java.io.File

/**
 * Plugin API for kUML code generators.
 *
 * V1: only the built-in "kotlin" generator is bundled.
 * The loading mechanism (JAR/ServiceLoader) is a V1.1 concern.
 */
public interface KumlCodeGenerator {
    /** Unique identifier, e.g. "kotlin", "java", "sql". */
    public val id: String

    /** Human-readable name shown in `kuml generate --help`. */
    public val displayName: String

    /**
     * Generate code from [diagram] into [outputDir].
     *
     * @param diagram The source diagram.
     * @param outputDir Directory where generated files are written. Created if absent.
     * @param options Generator-specific options (package name, etc.).
     * @return Ordered list of files that were written.
     */
    public fun generate(
        diagram: KumlDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File>
}

/** Thrown when code generation fails for a business-logic reason. */
public class CodeGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
