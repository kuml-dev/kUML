package dev.kuml.core.script

import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Evaluates `*.kuml.kts` scripts using [KumlScriptCompilationConfiguration].
 *
 * The host is reused across calls — do not create multiple instances.
 *
 * Example:
 * ```kotlin
 * val result = KumlScriptHost.eval(File("hello.kuml.kts"))
 * val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
 * check(errors.isEmpty()) { errors.joinToString("\n") }
 * ```
 */
object KumlScriptHost {
    private val host = BasicJvmScriptingHost()
    private val compilationConfig = createJvmCompilationConfigurationFromTemplate<KumlScript>()

    /**
     * Evaluates a kUML script from a [File].
     *
     * @param file A `*.kuml.kts` file accessible on the local filesystem.
     */
    fun eval(file: File): ResultWithDiagnostics<EvaluationResult> =
        host.eval(
            script = file.toScriptSource(),
            compilationConfiguration = compilationConfig,
            evaluationConfiguration = null,
        )

    /**
     * Evaluates a kUML script from an inline string.
     *
     * Useful for tests and REPL-like integrations.
     *
     * @param code kUML script source code.
     * @param fileName Virtual file name shown in error messages.
     */
    fun eval(
        code: String,
        fileName: String = "inline.kuml.kts",
    ): ResultWithDiagnostics<EvaluationResult> =
        host.eval(
            script = code.toScriptSource(fileName),
            compilationConfiguration = compilationConfig,
            evaluationConfiguration = null,
        )
}
