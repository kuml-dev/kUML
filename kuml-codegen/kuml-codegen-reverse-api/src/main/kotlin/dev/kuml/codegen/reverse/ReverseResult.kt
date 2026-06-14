package dev.kuml.codegen.reverse

import dev.kuml.core.model.KumlModel

/** Discriminated result of a reverse analysis. */
public sealed class ReverseResult {
    /** Success — model + diagnostics (non-empty WARN list is not fatal). */
    public data class Success(
        val model: KumlModel,
        val diagnostics: List<ReverseDiagnostic> = emptyList(),
        val filesAnalysed: Int,
        val elapsedMs: Long,
    ) : ReverseResult()

    /** Failure — one or more ERROR-level diagnostics. */
    public data class Failure(
        val errors: List<ReverseDiagnostic>,
    ) : ReverseResult()
}
