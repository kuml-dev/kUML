package io.kuml.core.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * kUML script definition.
 *
 * Files with the extension `*.kuml.kts` are compiled and evaluated using this
 * definition. The provided [defaultImports] make `io.kuml.core.model.*`,
 * `io.kuml.core.model.DiagramType.*`, and `io.kuml.core.dsl.*` available
 * without explicit import statements.
 *
 * Minimal script example:
 * ```kotlin
 * // hello.kuml.kts
 * diagram(name = "Hello kUML", type = DiagramType.CLASS) {}
 * ```
 */
@KotlinScript(
    displayName = "kUML Script",
    fileExtension = "kuml.kts",
    compilationConfiguration = KumlScriptCompilationConfiguration::class,
)
abstract class KumlScript

/**
 * Compilation configuration for `*.kuml.kts` scripts.
 *
 * Uses [dependenciesFromCurrentContext] so the classpath of the calling JVM
 * (which includes `kuml-core-model` and `kuml-core-dsl`) is available inside
 * scripts without explicit dependency declarations.
 */
object KumlScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "io.kuml.core.model.*",
        "io.kuml.core.model.DiagramType.*",
        "io.kuml.core.dsl.*",
    )
})
