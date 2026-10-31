package dev.kuml.cli.workspace

import dev.kuml.cli.NoteChecks
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.diagnostics.ScriptProblem
import dev.kuml.core.script.diagnostics.ScriptProblems
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
 * Public so that kUML Desktop's MCP workspace tools reuse exactly the CLI's evaluation logic.
 *
 * Log hygiene: findings carry only code, path, line, block index and counts — never diagram
 * names or other block content.
 */
public object WorkspaceBlockEvaluator {
    /**
     * Writes [source] to a temporary `<stem>.kuml.kts`, runs [block] with it, and always deletes
     * the temporary directory afterwards.
     */
    internal fun <T> withTempScript(
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
    internal fun sanitizeStem(raw: String): String {
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
    internal fun resolveWithin(
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
    public fun countDiagrams(
        doc: OkfDocument,
        block: KumlCodeBlock,
        blockIdx: Int,
    ): Result<Int> {
        val stem = sanitizeStem(block.name ?: "${doc.file.nameWithoutExtension}-${blockIdx + 1}")
        return try {
            withTempScript(stem = stem, source = block.source) { script ->
                val result = KumlScriptHost.eval(file = script)
                val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                val problems = ScriptProblems.describe(result = result, source = block.source, scriptFileName = script.name)
                if (errors.isNotEmpty() || result !is ResultWithDiagnostics.Success || problems.isNotEmpty()) {
                    Result.failure(BlockScriptException(problems = problems, fallback = errors.joinToString("\n") { it.message }))
                } else {
                    Result.success(DiagramExtractor.countRenderableDiagrams(result.value.returnValue))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Result of evaluating one block: diagram count plus the note findings of its first diagram. */
    public data class BlockEvaluation(
        val diagramCount: Int,
        val noteFindings: List<NoteChecks.Finding>,
    )

    /**
     * Like [countDiagrams], but also extracts the first diagram and runs [NoteChecks] on it, so
     * that unresolved or undrawable note anchors surface at validation time instead of
     * vanishing silently at render time.
     */
    public fun evaluateBlock(
        doc: OkfDocument,
        block: KumlCodeBlock,
        blockIdx: Int,
    ): Result<BlockEvaluation> {
        val stem = sanitizeStem(block.name ?: "${doc.file.nameWithoutExtension}-${blockIdx + 1}")
        return try {
            withTempScript(stem = stem, source = block.source) { script ->
                val result = KumlScriptHost.eval(file = script)
                val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                val problems = ScriptProblems.describe(result = result, source = block.source, scriptFileName = script.name)
                if (errors.isNotEmpty() || result !is ResultWithDiagnostics.Success || problems.isNotEmpty()) {
                    Result.failure(BlockScriptException(problems = problems, fallback = errors.joinToString("\n") { it.message }))
                } else {
                    val returnValue = result.value.returnValue
                    val count = DiagramExtractor.countRenderableDiagrams(returnValue)
                    val notes =
                        if (count == 0) {
                            emptyList()
                        } else {
                            runCatching { NoteChecks.of(DiagramExtractor.extractAny(returnValue = returnValue, input = script)) }
                                .getOrDefault(emptyList())
                        }
                    Result.success(BlockEvaluation(diagramCount = count, noteFindings = notes))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Maps a note finding to a workspace finding (code `KUML-NOTE-W00x` etc., line of the block). */
    public fun noteFinding(
        doc: OkfDocument,
        block: KumlCodeBlock,
        finding: NoteChecks.Finding,
    ): OkfFinding =
        OkfFinding(
            code = finding.code,
            severity = if (finding.isError) OkfSeverity.ERROR else OkfSeverity.WARNING,
            file = doc.relativePath,
            line = block.startLine,
            message = finding.message,
        )

    /**
     * Opt-in deep check for `workspace validate --evaluate`: evaluates every kuml block of
     * [documents]. Evaluation failures become `OKF-E-007`; blocks yielding more than one diagram
     * become `OKF-W-008` (promoted to ERROR when [strict]); unresolved or undrawable note anchors
     * become warnings `KUML-NOTE-W001`..`W003` carrying the note and anchor ids.
     */
    public fun evaluateDocuments(
        documents: List<OkfDocument>,
        strict: Boolean,
    ): List<OkfFinding> {
        val findings = mutableListOf<OkfFinding>()
        for (doc in documents) {
            doc.kumlBlocks.forEachIndexed { idx, block ->
                val evaluation = evaluateBlock(doc = doc, block = block, blockIdx = idx)
                evaluation.onSuccess { ev ->
                    findings += ev.noteFindings.map { noteFinding(doc = doc, block = block, finding = it) }
                }
                val count = evaluation.map { it.diagramCount }
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
                    findings += failureFindings(doc = doc, block = block, blockIdx = idx, error = e)
                }
            }
        }
        return findings
    }

    /**
     * `OKF-E-007` findings for a failed block: one per script problem, each with the document line
     * (`block.startLine` is the opening fence, so block line N is document line `startLine + N`).
     */
    internal fun failureFindings(
        doc: OkfDocument,
        block: KumlCodeBlock,
        blockIdx: Int,
        error: Throwable,
    ): List<OkfFinding> {
        val problems = (error as? BlockScriptException)?.problems.orEmpty()
        if (problems.isEmpty()) {
            return listOf(
                OkfFinding(
                    code = "OKF-E-007",
                    severity = OkfSeverity.ERROR,
                    file = doc.relativePath,
                    line = block.startLine,
                    message = "Failed to evaluate kuml block: ${error.message?.lines()?.firstOrNull().orEmpty()}",
                ),
            )
        }
        return problems.map { p ->
            val docLine = p.line?.let { block.startLine + it } ?: block.startLine
            val where = p.line?.let { "line $docLine (block line $it)" } ?: "line ${block.startLine}"
            OkfFinding(
                code = "OKF-E-007",
                severity = OkfSeverity.ERROR,
                file = doc.relativePath,
                line = docLine,
                message = "kuml block #${blockIdx + 1} $where: ${p.message} [${p.code}]",
                suggestion = p.hint,
            )
        }
    }

    /** Failure of a block's script evaluation, carrying structured problems with positions. */
    public class BlockScriptException(
        public val problems: List<ScriptProblem>,
        fallback: String,
    ) : IllegalStateException(problems.firstOrNull()?.message ?: fallback.ifEmpty { "Script evaluation failed" })

    public fun multiDiagramFinding(
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
