package dev.kuml.codegen.reverse

import java.nio.file.Path

/**
 * Input for a reverse analysis run.
 *
 * [Path] fields are not directly `@Serializable` — JSON serialization support
 * (with a PathSerializer module) is wired in V3.0.9 when the CLI consumes this DTO.
 * For V3.0.7 the DTO is used programmatically, serialization is not exercised.
 *
 * @property sourceRoots Source root paths (typically `src/main/java` etc.). Traversed recursively.
 * @property classpathJars Optional external classpath for symbol resolution (dependency jars).
 * @property includeGlobs File patterns to include (default: `**&#47;*.java`).
 * @property excludeGlobs File patterns to explicitly exclude.
 * @property targetModelName Name of the resulting [dev.kuml.core.model.KumlModel].
 */
public data class ReverseRequest(
    val sourceRoots: List<Path>,
    val classpathJars: List<Path> = emptyList(),
    val includeGlobs: List<String> = listOf("**/*.java"),
    val excludeGlobs: List<String> = emptyList(),
    val targetModelName: String = "ReverseEngineered",
)
