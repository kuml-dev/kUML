package dev.kuml.jetbrains

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.kuml.core.script.KumlScript
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource
import java.io.File
import java.net.URI
import java.net.URL
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
 *
 * ## Suppress rationale
 *
 * [ScriptDefinitionsSource] is deprecated in newer Kotlin plugin versions. However,
 * the `scriptDefinitionsSource` extension point is the only EP that works in **both**
 * K1 and K2 mode (confirmed IntelliJ 2024.3 / build 243+). The suggested replacement
 * (`scriptDefinitionsProvider` / `ScriptDefinitionsProvider`) fails to instantiate in
 * K2 mode with "Cannot find suitable constructor, expected (Project)". Suppressed
 * until JetBrains provides a stable K2-compatible replacement EP.
 */
@Suppress("DEPRECATION")
public class KumlScriptDefinitionsSource(
    @Suppress("unused") private val project: Project,
) : ScriptDefinitionsSource {
    private companion object {
        private val LOG = Logger.getInstance(KumlScriptDefinitionsSource::class.java)

        /**
         * All jars bundled in this plugin's `lib/` directory — the real location of
         * the kUML model + DSL classes the script must resolve against.
         *
         * Resolved **purely via the JDK**: we ask this class's own class loader for the
         * URL of its `.class` resource. Inside a packaged plugin that is a `jar:` URL
         * pointing at the plugin's main jar in `lib/`; its parent directory (`lib/`)
         * holds every bundled kUML DSL jar.
         *
         * This deliberately avoids the IntelliJ `PluginManager` / `PluginManagerCore`
         * APIs — both `getPlugin(PluginId)` and `getPluginByClass(Class)` are
         * `@ApiStatus.Internal` in recent platform versions and trip the JetBrains
         * Marketplace verifier ("internal API usage"). `Class.getResource(...)` returns
         * a usable `jar:` URL where `protectionDomain.codeSource.location` would be empty
         * under IntelliJ's plugin class loader, so this path works inside the running IDE.
         *
         * During local unit tests the class is loaded from an exploded `build/classes`
         * directory (a `file:` URL, not `jar:`) → returns an empty list, which is the
         * same best-effort fallback as before (the definition is still built).
         */
        private fun collectKumlClasspath(): List<File> {
            val ownJar = ownPluginJar() ?: return emptyList()
            val libDir = ownJar.parentFile?.toPath() ?: return emptyList()
            if (!Files.isDirectory(libDir)) return emptyList()
            return runCatching {
                Files.newDirectoryStream(libDir, "*.jar").use { stream -> stream.map { it.toFile() } }
            }.getOrDefault(emptyList())
        }

        /**
         * The jar file this plugin class was loaded from, or `null` if it is not loaded
         * from a `jar:` URL (e.g. an exploded class directory during local development).
         */
        private fun ownPluginJar(): File? {
            val clazz = KumlScriptDefinitionsSource::class.java
            val resourceName = clazz.name.replace('.', '/') + ".class"
            val url: URL = clazz.classLoader?.getResource(resourceName) ?: return null
            if (url.protocol != "jar") return null
            // url.path looks like: file:/…/plugin/lib/kuml-…​.jar!/dev/kuml/…​/Foo.class
            val path = url.path
            val separator = path.indexOf("!/")
            val filePart = if (separator >= 0) path.substring(0, separator) else path
            return runCatching { File(URI(filePart)) }.getOrNull()
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
