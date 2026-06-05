package dev.kuml.core.config

import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Host für `*.kuml.config.kts`-Scripts.
 *
 * Singleton — paralleler Mechanismus zu `dev.kuml.core.script.KumlScriptHost`.
 */
public object KumlConfigScriptHost {
    private val host = BasicJvmScriptingHost()
    private val compilationConfig =
        createJvmCompilationConfigurationFromTemplate<KumlConfigScript>()

    public fun eval(file: File): ResultWithDiagnostics<EvaluationResult> =
        host.eval(
            script = file.toScriptSource(),
            compilationConfiguration = compilationConfig,
            evaluationConfiguration = null,
        )

    public fun eval(
        code: String,
        fileName: String = "inline.kuml.config.kts",
    ): ResultWithDiagnostics<EvaluationResult> =
        host.eval(
            script = code.toScriptSource(fileName),
            compilationConfiguration = compilationConfig,
            evaluationConfiguration = null,
        )
}
