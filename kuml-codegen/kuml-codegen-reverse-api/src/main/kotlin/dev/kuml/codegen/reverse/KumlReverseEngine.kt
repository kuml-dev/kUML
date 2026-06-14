package dev.kuml.codegen.reverse

/**
 * Language-agnostic reverse engine interface.
 *
 * Implementations are registered via ServiceLoader (META-INF/services/...) and
 * resolved through [ReverseEngineRegistry]. The [id] is the routing axis for
 * `kuml reverse --lang <id>` (V3.0.9).
 *
 * [analyze] is `suspend` — JavaParser is synchronous and runs internally under
 * `withContext(Dispatchers.IO)`. Kotlin-PSI (V3.0.8) will use the same contract.
 */
public interface KumlReverseEngine {
    /** Machine-readable id used in ServiceLoader routing and CLI `--lang` choice. */
    public val id: String

    /** Human-readable one-liner. */
    public val description: String

    /** Analyse the source roots in the request and produce a UmlModel-bearing result. */
    public suspend fun analyze(request: ReverseRequest): ReverseResult
}
