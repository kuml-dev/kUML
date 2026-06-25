package dev.kuml.jetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.kuml.core.script.KumlScript
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource
import java.io.File
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

/**
 * K2-native source for the kUML `*.kuml.kts` script definition (registered via the
 * `org.jetbrains.kotlin.scriptDefinitionsSource` extension point).
 *
 * ## The classpath problem this solves
 *
 * Building the definition from the [KumlScript] template alone is **not enough** in
 * the IDE. `KumlScriptCompilationConfiguration` resolves its classpath with
 * `dependenciesFromCurrentContext(wholeClasspath = true)`, which — inside IntelliJ —
 * captures the **platform** class loader's classpath (all the IntelliJ
 * `Contents/lib` jars) and **none** of the bundled kUML DSL jars
 * (`kuml-core-model`, `kuml-uml`, `kuml-c4`, `kuml-metamodel-blueprint`, …). The
 * `defaultImports` (`dev.kuml.uml.dsl.*` etc.) therefore point at classes that are
 * not on the resolution classpath → every DSL call (`classDiagram`, `classOf`, …)
 * shows up as an unresolved reference (verified via the diagnostic log).
 *
 * The fix appends an explicit [JvmDependency] over [collectPluginClasspath] (the
 * jars that actually contain the kUML model + DSL classes, resolved from this
 * plugin's class loader) to the template's compilation configuration, then rebuilds
 * the definition via [ScriptDefinition.FromConfigurations] (whose 3-arg constructor
 * is signature-stable across Kotlin versions, unlike the `FromTemplate` synthetic).
 *
 * ## Version-skew safety
 *
 * `FromTemplate` and `FromConfigurations` are both invoked **reflectively** through
 * their stable public constructors to dodge the Kotlin default-argument synthetic
 * mismatch between maven `kotlin-scripting:2.4.0` (what the kuml modules compile
 * against) and the bundled Kotlin plugin (build 261) loaded at runtime.
 */
public class KumlScriptDefinitionsSource(
    @Suppress("unused") private val project: Project,
) : ScriptDefinitionsSource {
    private companion object {
        private val LOG = Logger.getInstance(KumlScriptDefinitionsSource::class.java)
        private const val PLUGIN_ID = "dev.kuml.intellij"

        /**
         * All jars bundled in this plugin's `lib/` directory — the real location of
         * the kUML model + DSL classes the script must resolve against.
         *
         * We resolve them via [PluginManagerCore] (the plugin's `pluginPath`) rather
         * than via `Class.protectionDomain.codeSource.location`, because IntelliJ's
         * `PluginClassLoader` does **not** populate a code-source location for plugin
         * classes — that reflective approach returns an empty list inside the IDE.
         */
        private fun collectKumlClasspath(): List<File> {
            val root =
                PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
                    ?: return emptyList()
            val libDir = root.resolve("lib")
            val dir = if (Files.isDirectory(libDir)) libDir else root
            return runCatching {
                Files.newDirectoryStream(dir, "*.jar").use { stream -> stream.map { it.toFile() } }
            }.getOrDefault(emptyList())
        }
    }

    override val definitions: Sequence<ScriptDefinition>
        get() {
            return try {
                // 1. Base definition from the @KotlinScript template (fileExtension,
                //    defaultImports, baseClass all derived from KumlScript).
                val fromTemplateCtor =
                    ScriptDefinition.FromTemplate::class.java.getConstructor(
                        ScriptingHostConfiguration::class.java,
                        KClass::class.java,
                        KClass::class.java,
                    )
                val baseDef =
                    fromTemplateCtor.newInstance(
                        defaultJvmScriptingHostConfiguration,
                        KumlScript::class,
                        KumlScript::class,
                    ) as ScriptDefinition

                // 2. Append the real kUML DSL classpath so the implicit imports resolve.
                val kumlClasspath = collectKumlClasspath()
                val fixedCompilation =
                    ScriptCompilationConfiguration(baseDef.compilationConfiguration) {
                        dependencies.append(JvmDependency(kumlClasspath))
                    }

                // 3. Rebuild via the signature-stable FromConfigurations constructor.
                val fromConfigCtor =
                    ScriptDefinition.FromConfigurations::class.java.getConstructor(
                        ScriptingHostConfiguration::class.java,
                        ScriptCompilationConfiguration::class.java,
                        ScriptEvaluationConfiguration::class.java,
                    )
                val def =
                    fromConfigCtor.newInstance(
                        defaultJvmScriptingHostConfiguration,
                        fixedCompilation,
                        baseDef.evaluationConfiguration,
                    ) as ScriptDefinition

                LOG.warn(
                    "kUML scriptDefinitionsSource OK: fileExtension='${def.fileExtension}', " +
                        "kumlClasspathEntries=${kumlClasspath.size} -> ${kumlClasspath.map { it.name }}",
                )
                sequenceOf(def)
            } catch (t: Throwable) {
                LOG.warn("kUML scriptDefinitionsSource FAILED to build definition", t)
                emptySequence()
            }
        }
}
