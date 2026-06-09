package dev.kuml.jetbrains

/**
 * A diagnostic produced by the kUML validator, ready to be attached to the
 * IntelliJ annotation holder.
 *
 * Created in the background annotation step; applied to the PsiFile in the
 * foreground apply step.
 */
data class KumlDiagnostic(
    val message: String,
    /** 1-based line number from the Kotlin script diagnostic. */
    val line: Int,
    /** 1-based column. */
    val column: Int,
    val severity: DiagnosticSeverity,
) {
    enum class DiagnosticSeverity { ERROR, WARNING, INFO }
}
