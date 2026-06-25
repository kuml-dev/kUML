package dev.kuml.jetbrains

import kotlin.script.experimental.intellij.ScriptDefinitionsProvider

/**
 * Provides the `KumlScript` script template to IntelliJ's Kotlin scripting
 * infrastructure.
 *
 * IntelliJ discovers script templates via the
 * `org.jetbrains.kotlin.scriptDefinitionsProvider` extension point. Returning
 * the fully-qualified name of [dev.kuml.core.script.KumlScript] is enough — the
 * Kotlin plugin reads its [kotlin.script.experimental.annotations.KotlinScript]
 * annotation (file extension, default imports, compilation configuration) and
 * wires everything up.
 *
 * The classpath we hand back via [getDefinitionClasspath] is the bundle of JARs
 * the script template (and its `defaultImports`) live in. Without this, the
 * editor sees `KumlScript` but can't find `dev.kuml.core.model.*`,
 * `dev.kuml.uml.dsl.*`, etc., when resolving the implicit imports.
 *
 * We do **not** override `getCompilationConfigurationClassName()` — the default
 * implementation reads it from the `@KotlinScript` annotation, which already
 * points at `KumlScriptCompilationConfiguration`.
 */
public class KumlScriptDefinitionsProvider : ScriptDefinitionsProvider {
    override val id: String = "kUML Script Definitions"

    override fun getDefinitionClasses(): Iterable<String> = listOf(KUML_SCRIPT_TEMPLATE_FQN)

    override fun getDefinitionsClassPath(): Iterable<java.io.File> = collectPluginClasspath()

    /**
     * Re-use existing definitions from the project model (e.g. another plugin's
     * script setup) — kUML doesn't extend anyone else's, so we pass through.
     */
    override fun useDiscovery(): Boolean = false

    public companion object {
        public const val KUML_SCRIPT_TEMPLATE_FQN: String = "dev.kuml.core.script.KumlScript"
    }
}

/**
 * Collects the JARs (or class output dirs in dev) that the kUML script
 * template + its default-import packages live in.
 *
 * Why this is needed: IntelliJ's Kotlin scripting engine evaluates the
 * `@KotlinScript(compilationConfiguration = …)` reference at editor time, and
 * resolves imports against the provided classpath. We point it at the plugin's
 * own classloader-derived JARs — those bundle `kuml-core-script`,
 * `kuml-core-model`, `kuml-core-dsl`, `kuml-metamodel-uml`, `kuml-metamodel-c4`,
 * etc. (see `build.gradle.kts`).
 */
internal fun collectPluginClasspath(): List<java.io.File> {
    val markers =
        listOf(
            // Each entry is a class whose containing JAR/dir we want on the
            // script definition classpath. We resolve via reflection so this
            // list stays declarative.
            "dev.kuml.core.script.KumlScript",
            "dev.kuml.core.model.KumlDiagram",
            "dev.kuml.core.dsl.DiagramType",
            "dev.kuml.uml.UmlClass",
            "dev.kuml.c4.model.C4Workspace",
            "dev.kuml.blueprint.model.BlueprintModel", // V0.18 — blueprint default imports
        )
    val classLoader = KumlScriptDefinitionsProvider::class.java.classLoader
    return markers
        .mapNotNull { fqn ->
            try {
                val clazz = Class.forName(fqn, false, classLoader)
                val location = clazz.protectionDomain?.codeSource?.location ?: return@mapNotNull null
                java.io.File(location.toURI())
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.distinct()
}
