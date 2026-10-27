package dev.kuml.cli.workspace

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.markdown.KumlCodeBlock
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Shared mechanics for running a Markdown ` ```kuml ` block through the script host:
 * the temp-script handling used by [WorkspaceRenderer], and the opt-in evaluation used by
 * `workspace validate --evaluate`.
 *
 * Trust model: evaluating a block executes arbitrary Kotlin script code — exactly like
 * `kuml render`. [evaluateDocuments] is therefore only ever called when the user passes
 * `--evaluate` explicitly; a plain `validate` stays purely structural.
 *
 * Log hygiene: findings carry only code, path, line, block index and counts — never diagram
 * names or other block content.
 */
internal object WorkspaceBlockEvaluator {
    /**
     * Writes [source] to a temporary `<stem>.kuml.kts`, runs [block] with it, and always deletes
     * the temporary directory afterwards.
     */
    fun <T> withTempScript(
        stem: String,
        source: String,
        block: (File) -> T,
    ): T {
        val tempDir = Files.createTempDirectory("kuml-okf-render").toFile()
        try {
            val tempScript = resolveWithin(base = tempDir, childName = "$stem.kuml.kts")
            tempScript.writeText(source, Charsets.UTF_8)
            return block(tempScript)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Reduces an attacker-controlled `name` attribute (or a document stem derived
     * from a scanned path) to a safe file-name stem: strips any path components
     * (`/`, `\`, `..`) by keeping only the last path segment, then replaces every
     * character outside `[A-Za-z0-9._-]` with `_`. Falls back to `"block"` if
     * nothing safe remains (e.g. a name of just `".."` or `"///"`).
     *
     * This is the primary defence against path traversal via `name="../../etc/passwd"`
     * in a ` ```kuml {name="..."} ` block — see ADR-0011 spike security review.
     */
    fun sanitizeStem(raw: String): String {
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val trimmed = cleaned.trim('.', '_', '-')
        return trimmed.ifEmpty { "block" }
    }

    /**
     * Resolves [childName] under [base] and asserts the result stays inside
     * [base] after normalization — a defence-in-depth backstop in case
     * [sanitizeStem] is ever bypassed or a future caller forgets to apply it.
     */
    fun resolveWithin(
        base: File,
        childName: String,
    ): File {
        val resolved = File(base, childName)
        val normalizedResolved = resolved.toPath().normalize()
        val normalizedBase = base.toPath().normalize()
        require(normalizedResolved.startsWith(normalizedBase)) {
            "Refusing to write outside of $normalizedBase: $normalizedResolved"
        }
        return resolved
    }

    /**
     * Evaluates [block] and returns the number of renderable diagrams it produces, or a failure
     * whose message describes the script error.
     */
    fun countDiagrams(
        doc: OkfDocument,
        block: KumlCodeBlock,
        blockIdx: Int,
    ): Result<Int> {
        val stem = sanitizeStem(block.name ?: "${doc.file.nameWithoutExtension}-${blockIdx + 1}")
        return try {
            withTempScript(stem = stem, source = block.source) { script ->
                val result = KumlScriptHost.eval(file = script)
                val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                if (errors.isNotEmpty() || result !is ResultWithDiagnostics.Success) {
                    Result.failure(IllegalStateException("Script evaluation failed:\n" + errors.joinToString("\n") { it.message }))
                } else {
                    Result.success(DiagramExtractor.countRenderableDiagrams(result.value.returnValue))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Opt-in deep check for `workspace validate --evaluate`: evaluates every kuml block of
     * [documents]. Evaluation failures become `OKF-E-007`; blocks yielding more than one diagram
     * become `OKF-W-008` (promoted to ERROR when [strict]).
     */
    fun evaluateDocuments(
        documents: List<OkfDocument>,
        strict: Boolean,
    ): List<OkfFinding> {
        val findings = mutableListOf<OkfFinding>()
        for (doc in documents) {
            doc.kumlBlocks.forEachIndexed { idx, block ->
                val count = countDiagrams(doc = doc, block = block, blockIdx = idx)
                count.onSuccess { n ->
                    if (n > 1) findings += multiDiagramFinding(doc = doc, block = block, blockIdx = idx, count = n, strict = strict)
                    if (n == 0) {
                        // `render` fails on the same block (DiagramExtractor.extractAny throws).
                        findings +=
                            OkfFinding(
                                code = "OKF-E-007",
                                severity = OkfSeverity.ERROR,
                                file = doc.relativePath,
                                line = block.startLine,
                                message = "kuml block #${idx + 1} evaluates but produces no renderable diagram.",
                                suggestion = "End the block with a complete diagram or model expression, not a bare sub-diagram.",
                            )
                    }
                }
                count.onFailure { e ->
                    findings +=
                        OkfFinding(
                            code = "OKF-E-007",
                            severity = OkfSeverity.ERROR,
                            file = doc.relativePath,
                            line = block.startLine,
                            message = "Failed to evaluate kuml block: ${e.message}",
                        )
                }
            }
        }
        return findings
    }

    fun multiDiagramFinding(
        doc: OkfDocument,
        block: KumlCodeBlock,
        blockIdx: Int,
        count: Int,
        strict: Boolean,
    ): OkfFinding =
        OkfFinding(
            code = "OKF-W-008",
            severity = if (strict) OkfSeverity.ERROR else OkfSeverity.WARNING,
            file = doc.relativePath,
            line = block.startLine,
            message = "kuml block #${blockIdx + 1} produces $count diagrams; only the first is rendered, ${count - 1} ignored.",
            suggestion = "Split each diagram into its own kuml block (and ideally its own document, see OKF-W-004).",
        )
}
