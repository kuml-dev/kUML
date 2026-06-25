package dev.kuml.jetbrains

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Validates `.kuml.kts` files in a background thread and reports compilation
 * errors as inline squiggles.
 *
 * Validation runs via the external `kuml` CLI (`kuml diagnostics`), not the
 * in-process Kotlin scripting host: that host (`BasicJvmScriptingHost`) is not
 * reachable from the IDE plugin classloader, so the previous in-process approach
 * silently threw `NoClassDefFoundError` and produced no diagnostics at all. The
 * CLI carries its own consistent classpath and emits diagnostics with precise
 * line/column locations — see [KumlCliDiagnostics].
 *
 * Caches the last result per file-modification-stamp to avoid redundant CLI runs.
 */
class KumlAnnotator : ExternalAnnotator<KumlAnnotator.Info, List<KumlDiagnostic>>() {
    data class Info(
        val text: String,
        val modStamp: Long,
        val fileName: String,
    )

    companion object {
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

        val diagnostics =
            try {
                KumlCliDiagnostics.analyze(info.text, info.fileName)
            } catch (_: Throwable) {
                // Degrade gracefully — never crash the editor over a failed CLI call.
                emptyList()
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
