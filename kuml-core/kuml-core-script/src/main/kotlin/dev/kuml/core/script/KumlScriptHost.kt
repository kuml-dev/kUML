package dev.kuml.core.script

import dev.kuml.c4.dsl.C4Ids
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
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

    /**
     * The historical, **unfiltered** compilation config: `wholeClasspath = true`
     * (see [KumlScriptCompilationConfiguration]). Used by the trusted in-process
     * path — the operator vouches for their own CLI script.
     */
    private val compilationConfig = createJvmCompilationConfigurationFromTemplate<KumlScript>()

    /**
     * The **curated** compilation config for the sandbox worker path (Welle 7,
     * layer B). Instead of `wholeClasspath = true`, the script-visible dependency
     * classpath is narrowed to only the jars a legitimate kUML DSL script needs
     * (see [SandboxClasspath.curatedEntries]) — the DSL + metamodel jars,
     * kotlin-stdlib, kotlin-reflect, kotlinx-serialization/atomicfu/io. Dangerous
     * classpath jars (JNA, the kotlin-compiler-embeddable, coroutines-debug, …)
     * are **not** on it, so a script simply cannot reference their classes — the
     * compiler reports an unresolved reference at compile time, before any script
     * body runs.
     *
     * This is the control that actually works against **classpath** classes.
     * `baseClassLoader` filtering ([AllowlistClassLoader]) alone does NOT stop a
     * class that is on the compiled-script loader's own classpath URLs — that
     * loader resolves it directly without consulting the base loader. Narrowing
     * the classpath removes the URLs; the allowlist base loader is then the
     * belt-and-braces second filter for anything resolved via the parent.
     *
     * Platform JDK modules (`java.*`, most `jdk.*`) live in the boot module layer
     * and are resolved by neither the classpath nor the base loader — they are
     * **not blockable** by this layer. That is the documented Option-B limit; the
     * OS cage (Wellen 4-6) is what neutralises `java.io`/`java.net`/`Runtime`.
     */
    private val curatedCompilationConfig: ScriptCompilationConfiguration =
        ScriptCompilationConfiguration(compilationConfig) {
            jvm {
                updateClasspath(SandboxClasspath.curatedEntries())
            }
        }

    /**
     * Evaluates a kUML script from a [File].
     *
     * @param file A `*.kuml.kts` file accessible on the local filesystem.
     * @param evaluationClassLoader Optional **base classloader** for evaluation.
     *   When non-null (sandbox worker path) two things happen together:
     *   (1) compilation uses the **curated** classpath ([curatedCompilationConfig])
     *   so dangerous classpath jars are invisible to the script, and (2) the
     *   supplied loader (an [AllowlistClassLoader]) is pinned as [baseClassLoader]
     *   as a second filter. When null (trusted in-process path) neither applies —
     *   the historical `wholeClasspath = true` + default loader behaviour is used,
     *   unchanged. This keeps the whole Welle-7 restriction confined to the
     *   sandbox worker and never touching the trusted CLI/in-process path.
     */
    fun eval(
        file: File,
        evaluationClassLoader: ClassLoader? = null,
    ): ResultWithDiagnostics<EvaluationResult> {
        C4Ids.resetForScript()
        return host.eval(
            script = file.toScriptSource(),
            compilationConfiguration = if (evaluationClassLoader != null) curatedCompilationConfig else compilationConfig,
            evaluationConfiguration = evaluationConfigFor(evaluationClassLoader),
        )
    }

    /**
     * Builds the evaluation configuration. With a null [classLoader] this returns
     * `null` (kotlin-scripting's default: evaluate against the host's own
     * classloader — the pre-Welle-7 behaviour, unchanged for the in-process path).
     * With a non-null [classLoader] it pins that loader as [baseClassLoader], so
     * the compiled script's class references resolve through the filtering loader.
     */
    private fun evaluationConfigFor(classLoader: ClassLoader?): ScriptEvaluationConfiguration? =
        classLoader?.let { cl ->
            ScriptEvaluationConfiguration {
                jvm {
                    baseClassLoader(cl)
                }
            }
        }

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
    ): ResultWithDiagnostics<EvaluationResult> {
        C4Ids.resetForScript()
        return host.eval(
            script = code.toScriptSource(fileName),
            compilationConfiguration = compilationConfig,
            evaluationConfiguration = null,
        )
    }
}
