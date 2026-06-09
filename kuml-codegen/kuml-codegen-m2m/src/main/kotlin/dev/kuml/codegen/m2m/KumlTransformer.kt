package dev.kuml.codegen.m2m

/**
 * Core interface for model-to-model and model-to-text transformations.
 *
 * A transformer consumes a typed source [S] and produces a typed result [T].
 * The [TransformContext] carries cross-cutting concerns: options and
 * configuration the transformer can query without polluting the type signature.
 *
 * V2.0.21 provides [dev.kuml.codegen.m2m.jpa.UmlToJpaTransformer] as the first
 * production implementation. Future transformers (UmlToRest, UmlToK8s, C4ToUml, …)
 * are independent waves registered via ServiceLoader.
 */
public interface KumlTransformer<S, T> {
    /** Machine-readable id used in `kuml transform --transformer <id>`. */
    public val id: String

    /** Human-readable one-liner shown in `kuml transform --list-transformers`. */
    public val description: String

    public fun transform(
        source: S,
        ctx: TransformContext,
    ): TransformResult<T>
}

/**
 * Cross-cutting context passed to every [KumlTransformer.transform] call.
 *
 * @property options Arbitrary string key/value pairs (e.g. `"package"`, `"dialect"`).
 */
public data class TransformContext(
    val options: Map<String, String> = emptyMap(),
)

/** Discriminated result of a [KumlTransformer.transform] call. */
public sealed class TransformResult<out T> {
    /** Successful transformation — carries the output and the traceability record. */
    public data class Success<T>(
        val output: T,
        val trace: TransformTrace,
    ) : TransformResult<T>()

    /** Failed transformation — carries one or more diagnostic errors. */
    public data class Failure(
        val errors: List<TransformError>,
    ) : TransformResult<Nothing>()
}

/** A single diagnostic error emitted by a transformer. */
public data class TransformError(
    val message: String,
    val elementId: String? = null,
)

/**
 * A single traceability link from a source model element to a generated artifact.
 *
 * @property sourceElementId Stable id of the input model element.
 * @property targetArtifactId Logical id of the generated artifact (e.g. file path).
 * @property ruleId The rule that produced this link.
 */
public data class TraceabilityLink(
    val sourceElementId: String,
    val targetArtifactId: String,
    val ruleId: String,
)

/**
 * Immutable collection of [TraceabilityLink]s produced during a transformation.
 *
 * Use [plus] to accumulate links without mutation.
 */
public data class TransformTrace(
    val links: List<TraceabilityLink> = emptyList(),
) {
    public fun plus(link: TraceabilityLink): TransformTrace = copy(links = links + link)
}
