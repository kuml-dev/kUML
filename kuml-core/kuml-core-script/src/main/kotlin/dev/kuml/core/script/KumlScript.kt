package dev.kuml.core.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * kUML script definition.
 *
 * Files with the extension `*.kuml.kts` are compiled and evaluated using this
 * definition. The provided [defaultImports] make the core model types, DSL
 * builders, and UML metamodel elements available without explicit imports.
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
 * (which includes `kuml-core-model`, `kuml-core-dsl`, and `kuml-metamodel-uml`)
 * is available inside scripts without explicit dependency declarations.
 */
object KumlScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        // Core model types
        "dev.kuml.core.model.*",
        "dev.kuml.core.model.DiagramType.*",
        // DSL entry-points (diagram, umlModel)
        "dev.kuml.core.dsl.*",
        // UML DSL builders (classOf, interfaceOf, enumOf, `package`, association, …)
        "dev.kuml.uml.dsl.*",
        // UML metamodel (Phase 1+)
        "dev.kuml.uml.*",
        "dev.kuml.uml.Visibility.*",
        "dev.kuml.uml.AggregationKind.*",
    )
})
