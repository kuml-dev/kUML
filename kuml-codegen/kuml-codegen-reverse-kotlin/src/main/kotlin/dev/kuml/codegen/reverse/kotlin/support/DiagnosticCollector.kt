package dev.kuml.codegen.reverse.kotlin.support

import dev.kuml.codegen.reverse.ReverseDiagnostic

/**
 * Collects [ReverseDiagnostic] entries during a reverse analysis pass.
 *
 * Thread-safe: all mutation is synchronized on the collector instance.
 */
internal class DiagnosticCollector {
    private val diagnostics = mutableListOf<ReverseDiagnostic>()

    @Synchronized
    fun error(
        code: String,
        message: String,
        file: String? = null,
        line: Int? = null,
    ) {
        diagnostics +=
            ReverseDiagnostic(
                severity = ReverseDiagnostic.Severity.ERROR,
                code = code,
                message = message,
                file = file,
                line = line,
            )
    }

    @Synchronized
    fun warn(
        code: String,
        message: String,
        file: String? = null,
        line: Int? = null,
    ) {
        diagnostics +=
            ReverseDiagnostic(
                severity = ReverseDiagnostic.Severity.WARN,
                code = code,
                message = message,
                file = file,
                line = line,
            )
    }

    @Synchronized
    fun info(
        code: String,
        message: String,
        file: String? = null,
        line: Int? = null,
    ) {
        diagnostics +=
            ReverseDiagnostic(
                severity = ReverseDiagnostic.Severity.INFO,
                code = code,
                message = message,
                file = file,
                line = line,
            )
    }

    @Synchronized
    fun hasErrors(): Boolean = diagnostics.any { it.severity == ReverseDiagnostic.Severity.ERROR }

    @Synchronized
    fun errors(): List<ReverseDiagnostic> = diagnostics.filter { it.severity == ReverseDiagnostic.Severity.ERROR }.toList()

    @Synchronized
    fun all(): List<ReverseDiagnostic> = diagnostics.toList()
}
