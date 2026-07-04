package dev.kuml.core.script

import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Shared evaluate-then-extract logic used by **both** [InProcessScriptEvaluator]
 * (running in the MCP server JVM) and the child-process worker
 * ([ScriptWorkerMain]).
 *
 * Keeping this in one place guarantees the in-process and out-of-process paths
 * behave identically: same guard, same eval, same [DiagramExtractor.extractAny],
 * same failure classification. That is exactly the interface-contract shared by
 * the two evaluators' tests.
 *
 * V0.23.3.
 */
internal object ScriptEvaluationCore {
    /**
     * Guards, evaluates, and extracts a diagram from [source]. Writes the script
     * to a temp file (the Kotlin scripting host is happiest with a real file),
     * evaluates it, and always deletes the temp file afterwards.
     *
     * Never throws for ordinary script problems — returns [EvaluatedScript.Failure].
     */
    internal fun evaluateAndExtract(
        source: String,
        fileName: String,
    ): EvaluatedScript {
        // Layer 1: cheap regex denylist, before the compiler is ever invoked.
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(
                FailureKind.GUARD,
                e.message ?: "kUML script rejected by security guard.",
            )
        }

        val tmp = Files.createTempFile("kuml-eval-", ".kuml.kts").toFile()
        return try {
            tmp.writeText(source)
            val evalResult = KumlScriptHost.eval(tmp)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Script evaluation failed:\n${sanitiseDiagnostics(errors.map { it.message }, tmp, fileName)}",
                )
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return EvaluatedScript.Failure(FailureKind.EVALUATION, "Script evaluation produced no result")

            val extracted =
                try {
                    DiagramExtractor.extractAny(success.value.returnValue, tmp)
                } catch (e: ScriptEvaluationException) {
                    return EvaluatedScript.Failure(
                        FailureKind.EVALUATION,
                        sanitiseMessage(e.message ?: "Script did not produce a renderable diagram.", tmp, fileName),
                    )
                }
            EvaluatedScript.Success(extracted)
        } catch (e: Throwable) {
            // Any other exception (e.g. an exception thrown *inside* the script
            // body at runtime) — classify as evaluation, sanitise the message.
            EvaluatedScript.Failure(
                FailureKind.EVALUATION,
                sanitiseMessage(e.message ?: e::class.simpleName ?: "Unknown script error", tmp, fileName),
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * Replaces the temp-file path (which leaks a server-internal absolute path)
     * with the caller-supplied virtual [fileName] in a diagnostic message.
     */
    private fun sanitiseMessage(
        message: String,
        tmp: File,
        fileName: String,
    ): String =
        message
            .replace(tmp.absolutePath, fileName)
            .replace(tmp.name, fileName)

    private fun sanitiseDiagnostics(
        messages: List<String>,
        tmp: File,
        fileName: String,
    ): String = messages.joinToString("\n") { sanitiseMessage(it, tmp, fileName) }
}
