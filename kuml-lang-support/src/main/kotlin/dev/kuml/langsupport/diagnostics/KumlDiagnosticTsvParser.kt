package dev.kuml.langsupport.diagnostics

/**
 * Parses the TSV emitted by `kuml diagnostics`
 * (`severity⇥startLine⇥startCol⇥endLine⇥endCol⇥message`, see DiagnosticsCommand).
 * Dependency-free; the CLI always exits 0, so callers must never gate on exit code.
 */
public object KumlDiagnosticTsvParser {
    public fun parse(output: String): List<KumlDiagnostic> =
        output
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split('\t', limit = 6)
                if (f.size < 6) return@mapNotNull null
                val startLine = f[1].toIntOrNull() ?: 1
                val startCol = f[2].toIntOrNull() ?: 1
                KumlDiagnostic(
                    message = f[5],
                    startLine = startLine,
                    startCol = startCol,
                    endLine = f[3].toIntOrNull() ?: startLine,
                    endCol = f[4].toIntOrNull() ?: startCol,
                    severity =
                        when (f[0].uppercase()) {
                            "ERROR", "FATAL" -> KumlDiagnostic.Severity.ERROR
                            "WARNING" -> KumlDiagnostic.Severity.WARNING
                            else -> KumlDiagnostic.Severity.INFO
                        },
                )
            }.toList()
}
