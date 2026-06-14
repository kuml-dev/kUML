@file:OptIn(
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.K1Deprecation::class,
)

package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.psi.KtFile

/**
 * Wraps a [KotlinCoreEnvironment] with proper Disposable lifecycle.
 *
 * Call [dispose] when done (or use via try/finally in the engine).
 */
internal class KotlinAnalysisSession(
    request: ReverseRequest,
    private val diagnostics: DiagnosticCollector,
) {
    private val disposable = Disposer.newDisposable("kuml-reverse-kotlin")
    private val env: KotlinCoreEnvironment

    init {
        val config = CompilerConfiguration()
        config.put(CommonConfigurationKeys.MODULE_NAME, request.targetModelName)
        config.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)

        val collector = CollectingMessageCollector(diagnostics)
        config.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)

        // Add source roots
        for (root in request.sourceRoots) {
            config.addKotlinSourceRoot(root.toString())
        }

        env =
            KotlinCoreEnvironment.createForProduction(
                disposable,
                config,
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
    }

    fun loadKtFiles(): List<KtFile> = env.getSourceFiles()

    fun dispose() {
        Disposer.dispose(disposable)
    }

    /**
     * Routes Kotlin compiler messages as INFO diagnostics.
     */
    private class CollectingMessageCollector(
        private val diagnostics: DiagnosticCollector,
    ) : MessageCollector {
        private var hasErrors = false

        override fun clear() {
            hasErrors = false
        }

        override fun hasErrors(): Boolean = hasErrors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            val file = location?.path
            val line = location?.line?.takeIf { it > 0 }

            when {
                severity.isError -> {
                    hasErrors = true
                    diagnostics.warn("REV-K-099", "Kotlin compiler: $message", file, line)
                }
                severity == CompilerMessageSeverity.WARNING -> {
                    diagnostics.info("REV-K-099", "Kotlin compiler warning: $message", file, line)
                }
                else -> {
                    // INFO and lower — swallow to keep diagnostic output clean
                }
            }
        }
    }
}
