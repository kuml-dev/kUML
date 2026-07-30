package dev.kuml.detekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.test.utils.KotlinAnalysisApiEngine
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl

/**
 * Minimal stand-in for `dev.detekt.test.lintWithContext`, kept local because the published
 * `dev.detekt:detekt-test:2.0.0-alpha.5` artifact cannot be resolved from outside the detekt
 * monorepo (its Gradle module metadata requests the `detekt-api-test-fixtures` capability,
 * which `detekt-api` never publishes a runtime jar for — see the comment in build.gradle.kts).
 * `detekt-test-utils` (`KotlinAnalysisApiEngine`, `createEnvironment`) resolves cleanly and is
 * all that is actually needed to drive a [RequiresAnalysisApi] rule in a test.
 */
fun <T> T.lintWithContext(
    environment: KotlinEnvironmentContainer,
    content: String,
    vararg dependencyContents: String,
): List<Finding> where T : Rule, T : RequiresAnalysisApi =
    KotlinAnalysisApiEngine().use { engine ->
        val ktFile =
            engine.compile(
                code = content,
                dependencyCodes = dependencyContents.toList(),
                javaSourceRoots = environment.javaSourceRoots,
                jvmClasspathRoots = environment.jvmClasspathRoots,
                allowCompilationErrors = true,
            )
        visitFile(ktFile, languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT)
    }

/** Minimal stand-in for `dev.detekt.test.TestConfig` (same reason as above). */
class SimpleTestConfig(
    private val values: Map<String, Any>,
) : Config {
    constructor(vararg pairs: Pair<String, Any>) : this(pairs.toMap())

    override val parent: Config? = null

    override fun subConfig(key: String): Config =
        @Suppress("UNCHECKED_CAST")
        SimpleTestConfig(values.getOrDefault(key, emptyMap<String, Any>()) as Map<String, Any>)

    override fun subConfigKeys(): Set<String> = values.keys

    override fun <T : Any> valueOrDefault(
        key: String,
        default: T,
    ): T =
        @Suppress("UNCHECKED_CAST")
        (values.getOrDefault(key, default) as T)

    override fun <T : Any> valueOrNull(key: String): T? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? T)
}
