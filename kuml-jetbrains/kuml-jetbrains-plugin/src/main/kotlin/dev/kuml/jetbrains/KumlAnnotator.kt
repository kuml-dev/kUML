package dev.kuml.jetbrains

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.kuml.core.script.KumlScriptHost
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Runs the kUML validator (`KumlScriptHost.eval`) on `.kuml.kts` files in a
 * background thread and reports compilation/validation errors as inline
 * squiggles.
 *
 * Caches the last result per file-modification-stamp to avoid redundant
 * compilation. Evaluation is capped at [EVAL_TIMEOUT_MS] milliseconds.
 *
 * V2.0.28a: reports script compilation errors (the same set reported by
 * `kuml validate`). OCL guard type errors are V2.0.28b once the expression
 * validator is wired to a PSI-level diagnostic source.
 */
class KumlAnnotator : ExternalAnnotator<KumlAnnotator.Info, List<KumlDiagnostic>>() {
    data class Info(
        val text: String,
        val modStamp: Long,
        val fileName: String,
    )

    companion object {
        private const val EVAL_TIMEOUT_MS = 10_000L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<KumlDiagnostic>>>()
    }

    override fun collectInformation(file: PsiFile): Info? {
        if (!file.name.endsWith(".kuml.kts")) return null
        return Info(
            text = file.text,
            modStamp = file.virtualFile?.modificationStamp ?: return null,
            fileName = file.virtualFile?.path ?: file.name,
        )
    }

    override fun doAnnotate(collectedInfo: Info?): List<KumlDiagnostic> {
        val info = collectedInfo ?: return emptyList()

        // Cache hit
        val cached = cache[info.fileName]
        if (cached != null && cached.first == info.modStamp) return cached.second

        ProgressManager.checkCanceled()

        val diagnostics = mutableListOf<KumlDiagnostic>()

        try {
            val future =
                java.util.concurrent.Executors.newSingleThreadExecutor().submit<Unit> {
                    val result = KumlScriptHost.eval(info.text, info.fileName)
                    result.reports
                        .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
                        .forEach { diag ->
                            val (line, col) =
                                diag.location?.let {
                                    it.start.line to it.start.col
                                } ?: (1 to 1)
                            diagnostics +=
                                KumlDiagnostic(
                                    message = diag.message,
                                    line = line,
                                    column = col,
                                    severity =
                                        when (diag.severity) {
                                            ScriptDiagnostic.Severity.ERROR, ScriptDiagnostic.Severity.FATAL ->
                                                KumlDiagnostic.DiagnosticSeverity.ERROR
                                            ScriptDiagnostic.Severity.WARNING ->
                                                KumlDiagnostic.DiagnosticSeverity.WARNING
                                            else -> KumlDiagnostic.DiagnosticSeverity.INFO
                                        },
                                )
                        }
                }
            future.get(EVAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // Compilation timed out — return no diagnostics; try again on next edit
        } catch (_: Exception) {
            // Any other error — silently swallow to not crash the editor
        }

        cache[info.fileName] = info.modStamp to diagnostics
        return diagnostics
    }

    override fun apply(
        file: PsiFile,
        annotationResult: List<KumlDiagnostic>?,
        holder: AnnotationHolder,
    ) {
        val diagnostics = annotationResult ?: return
        val text = file.text

        for (diag in diagnostics) {
            val offset = lineColToOffset(text, diag.line, diag.column)
            val range =
                if (offset >= 0) {
                    val end =
                        (offset until text.length).firstOrNull { c ->
                            text[c].isWhitespace() || text[c] == '(' || text[c] == ')'
                        } ?: (offset + 1)
                    TextRange(offset, end.coerceAtMost(text.length))
                } else {
                    TextRange(0, 1)
                }

            val severity =
                when (diag.severity) {
                    KumlDiagnostic.DiagnosticSeverity.ERROR -> HighlightSeverity.ERROR
                    KumlDiagnostic.DiagnosticSeverity.WARNING -> HighlightSeverity.WARNING
                    KumlDiagnostic.DiagnosticSeverity.INFO -> HighlightSeverity.INFORMATION
                }

            val builder = holder.newAnnotation(severity, diag.message).range(range)

            // Attach quick-fix based on message pattern
            KumlQuickFixFactory.quickFixFor(diag)?.let { builder.withFix(it) }

            builder.create()
        }
    }

    internal fun lineColToOffset(
        text: String,
        line: Int,
        col: Int,
    ): Int {
        var currentLine = 1
        var i = 0
        while (i < text.length) {
            if (currentLine == line) return (i + col - 1).coerceIn(0, text.length - 1)
            if (text[i] == '\n') currentLine++
            i++
        }
        return -1
    }
}
