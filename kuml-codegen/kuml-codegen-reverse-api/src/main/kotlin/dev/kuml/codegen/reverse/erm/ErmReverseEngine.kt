package dev.kuml.codegen.reverse.erm

import dev.kuml.codegen.reverse.ReverseRequest

/**
 * ERM-tracking sibling of [dev.kuml.codegen.reverse.KumlReverseEngine].
 *
 * [dev.kuml.codegen.reverse.ReverseResult] is hard-wired to a UML-bearing
 * [dev.kuml.core.model.KumlModel] — reusing it for a SQL DDL → ERM reverse
 * engine (V3.4.9) would force an ERM model through a UML-shaped envelope.
 * [ErmReverseEngine] and [ErmReverseResult] are the parallel, ERM-tracking
 * contract; [ReverseRequest] and [dev.kuml.codegen.reverse.ReverseDiagnostic]
 * are engine-agnostic and reused as-is.
 *
 * Implementations are registered via ServiceLoader (META-INF/services/...) and
 * resolved through [dev.kuml.codegen.reverse.erm.registry.ErmReverseEngineRegistry].
 * [dialect] is the routing axis for `kuml reverse --format sql --dialect <dialect>` (V3.4.9).
 */
public interface ErmReverseEngine {
    /** Machine-readable id used in ServiceLoader routing, e.g. `"sql-postgres"`. */
    public val id: String

    /** The SQL dialect this engine parses, e.g. `"postgres"`. */
    public val dialect: String

    /** Human-readable one-liner. */
    public val description: String

    /** Analyse the source roots in the request and produce an ErmModel-bearing result. */
    public suspend fun analyze(request: ReverseRequest): ErmReverseResult
}
