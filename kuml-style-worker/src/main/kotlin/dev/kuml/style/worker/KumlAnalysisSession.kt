package dev.kuml.style.worker

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import kotlin.io.path.Path

/** Synthetic file name of the wrapped source inside the standalone session. */
private const val WRAPPED_FILE_NAME = "script.kt"

/**
 * Builds a **standalone** Kotlin Analysis API session (own JDK SDK module +
 * one library module for [binaryRoots] + one source module for the wrapped
 * script) and runs [analyzeNamedArguments] over it.
 *
 * This is deliberately NOT [dev.detekt.test.utils.KotlinAnalysisApiEngine]
 * (`detekt-test-utils`) — that dependency is not needed at runtime (see the
 * module KDoc in `build.gradle.kts`); this ~30-line reimplementation is the
 * entire reason `detekt-test-utils` can stay a test-only dependency of
 * `:kuml-detekt-rules` instead of shipping in every kUML distribution.
 *
 * One fresh [Disposer] root is created and disposed per call — no session
 * state is reused across scripts (this worker handles exactly one script per
 * process invocation, so reuse is not a concern, but disposal correctness is
 * still verified: a leaked `Disposable` root would leak the whole PSI/module
 * graph for the process's lifetime).
 */
internal object KumlAnalysisSession {
    /** Production entry point: resolves `dev.kuml.*` symbols against real jars. */
    internal fun analyze(
        wrapped: WrappedKumlScript,
        binaryRoots: List<Path>,
    ): List<NamedArgumentFinding> =
        analyzeSources(primaryText = wrapped.wrappedText, fixtureSources = emptyList(), binaryRoots = binaryRoots)

    /**
     * Test-only entry point: resolves the wrapped script against additional
     * **in-source** fixture files (e.g. a small `dev.kuml.fixture` package
     * defining fake owned symbols) instead of real jars — the same
     * dependency-injection trick `RequireNamedArgumentsSpec`'s `lintWithContext`
     * uses on the detekt side (`dependencyCodes` compiled alongside the
     * snippet). Lets the analyzer's exemption logic be exercised end-to-end
     * without shipping/compiling a real `kuml-*` jar into this module's test
     * classpath (which would reintroduce the very dependency this module's
     * `build.gradle.kts` KDoc explains must never exist).
     */
    internal fun analyzeWithSourceFixtures(
        wrapped: WrappedKumlScript,
        fixtureSources: List<String>,
    ): List<NamedArgumentFinding> =
        analyzeSources(primaryText = wrapped.wrappedText, fixtureSources = fixtureSources, binaryRoots = emptyList())

    private fun analyzeSources(
        primaryText: String,
        fixtureSources: List<String>,
        binaryRoots: List<Path>,
    ): List<NamedArgumentFinding> {
        val disposable = Disposer.newDisposable("kuml-style-worker-analysis-session")
        try {
            val session =
                buildStandaloneAnalysisAPISession(disposable) {
                    buildKtModuleProvider {
                        platform = JvmPlatforms.defaultJvmPlatform
                        val jdkModule =
                            buildKtSdkModule {
                                addBinaryRootsFromJdkHome(Path(System.getProperty("java.home")), isJre = true)
                                platform = JvmPlatforms.defaultJvmPlatform
                                libraryName = "jdk"
                            }
                        val dslLibraryModule =
                            buildKtLibraryModule {
                                addBinaryRoots(binaryRoots)
                                platform = JvmPlatforms.defaultJvmPlatform
                                libraryName = "kuml-dsl-classpath"
                            }
                        addModule(
                            buildKtSourceModule {
                                addRegularDependency(jdkModule)
                                addRegularDependency(dslLibraryModule)
                                addSourceVirtualFile(LightVirtualFile(WRAPPED_FILE_NAME, primaryText))
                                fixtureSources.forEachIndexed { index, src ->
                                    addSourceVirtualFile(LightVirtualFile("fixture$index.kt", src))
                                }
                                platform = JvmPlatforms.defaultJvmPlatform
                                moduleName = "kuml-style-check-source"
                                languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT
                            },
                        )
                    }
                }
            val ktFile =
                session.modulesWithFiles.values
                    .flatten()
                    .filterIsInstance<KtFile>()
                    .single { it.name == WRAPPED_FILE_NAME }
            return analyzeNamedArguments(ktFile)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
