package dev.kuml.codegen.reverse

import kotlinx.serialization.Serializable

/**
 * A single diagnostic message produced during a reverse analysis.
 *
 * Diagnostic codes follow the pattern `REV-<LANG>-<NNN>`:
 * - `REV-J-<NNN>` — JavaParser engine (V3.0.7)
 * - `REV-K-<NNN>` — Kotlin PSI engine (V3.0.8)
 * - `REV-CORE-<NNN>` — Engine-agnostic API-level diagnostics
 */
@Serializable
public data class ReverseDiagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
) {
    @Serializable
    public enum class Severity { INFO, WARN, ERROR }
}
