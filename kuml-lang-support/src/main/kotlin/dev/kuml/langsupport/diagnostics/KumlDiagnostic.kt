package dev.kuml.langsupport.diagnostics

/**
 * A diagnostic produced by the kUML validator, ready to be attached to an
 * editor's annotation/diagnostics surface (IntelliJ annotation holder, LSP
 * `Diagnostic`, ...).
 *
 * Created in the background annotation step; applied to the document in the
 * foreground apply step.
 */
public data class KumlDiagnostic(
    val message: String,
    /** 1-based start line (Kotlin-scripting / TSV convention). */
    val startLine: Int,
    /** 1-based start column. */
    val startCol: Int,
    /** 1-based end line; equals startLine when the CLI emits no end location. */
    val endLine: Int,
    /** 1-based end column. */
    val endCol: Int,
    val severity: Severity,
) {
    public enum class Severity { ERROR, WARNING, INFO }
}
