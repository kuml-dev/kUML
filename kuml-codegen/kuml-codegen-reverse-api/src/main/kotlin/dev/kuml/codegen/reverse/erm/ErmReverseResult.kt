package dev.kuml.codegen.reverse.erm

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.erm.model.ErmModel

/** Discriminated result of an ERM reverse analysis (V3.4.9 — SQL DDL → ERM). */
public sealed class ErmReverseResult {
    /** Success — model + diagnostics (non-empty WARN list is not fatal). */
    public data class Success(
        val model: ErmModel,
        val diagnostics: List<ReverseDiagnostic> = emptyList(),
        val filesAnalysed: Int,
        val elapsedMs: Long,
    ) : ErmReverseResult()

    /** Failure — one or more ERROR-level diagnostics. */
    public data class Failure(
        val errors: List<ReverseDiagnostic>,
    ) : ErmReverseResult()
}
