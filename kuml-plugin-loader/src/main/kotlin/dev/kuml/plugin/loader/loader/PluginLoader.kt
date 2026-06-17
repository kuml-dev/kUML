package dev.kuml.plugin.loader.loader

import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.codegen.api.KumlCodeGeneratorProvider
import dev.kuml.layout.KumlLayoutEngineProvider
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.plugin.api.codegen.KumlCodegenPlugin
import dev.kuml.plugin.api.core.KumlPlugin
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.layout.KumlLayoutPlugin
import dev.kuml.plugin.api.renderer.KumlRendererPlugin
import dev.kuml.plugin.api.reverse.KumlReversePlugin
import dev.kuml.plugin.api.theme.KumlThemePlugin
import dev.kuml.plugin.loader.error.PluginLoadException
import dev.kuml.plugin.loader.error.VersionMismatchException
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.manifest.PluginManifestParser
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.scan.PluginScanPath
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.KumlThemeProvider
import dev.kuml.renderer.theme.core.ThemeRegistry
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Orchestrates plugin discovery, loading, and registration.
 *
 * ## Loading order
 * 1. Built-in classpath plugins (via ServiceLoader — backward-compatible)
 * 2. System-wide plugins (`$KUML_HOME/plugins/`)
 * 3. User-local plugins (`~/.kuml/plugins/`)
 * 4. Extra paths passed to [load]
 *
 * ## Class-loader isolation
 * Each external JAR gets its own [PluginClassLoader] (parent = plugin-api layer).
 * Built-in classpath plugins share the application class loader.
 *
 * ## Note on ReverseEngineRegistry
 * [dev.kuml.codegen.reverse.registry.ReverseEngineRegistry] is pure-ServiceLoader-based and
 * has no programmatic [register] method. External reverse plugins loaded via JAR therefore
 * require the host JVM's ServiceLoader to pick them up (e.g. via module-path or --class-path).
 * Wiring support for ReverseEngineRegistry is deferred to a future version.
 */
public object PluginLoader {
    private const val MANIFEST_ENTRY = "kuml-plugin.json"

    /**
     * Load all plugins from default scan paths plus any [extraPaths].
     *
     * @param runtimeVersion The current kUML version (used for compatibility checks)
     * @param extraPaths     Additional directories to scan for plugin JARs
     */
    public fun load(
        runtimeVersion: PluginVersion,
        extraPaths: List<Path> = emptyList(),
    ) {
        loadBuiltInsViaServiceLoader()
        val scanDirs = PluginScanPath.defaults() + extraPaths
        for (dir in scanDirs) {
            for (jar in PluginScanPath.jarsIn(dir)) {
                loadJar(jar, runtimeVersion)
            }
        }
    }

    /**
     * Load a single plugin JAR explicitly.
     *
     * @throws VersionMismatchException if the plugin's kumlVersionRange excludes [runtimeVersion]
     * @throws PluginLoadException      if the manifest is missing, invalid, or instantiation fails
     */
    public fun loadJar(
        jar: File,
        runtimeVersion: PluginVersion,
    ) {
        val manifestJson =
            readManifestFromJar(jar)
                ?: throw PluginLoadException("No $MANIFEST_ENTRY found in ${jar.name}")

        val manifest = PluginManifestParser.parse(manifestJson)
        checkVersion(manifest, runtimeVersion)

        val classLoader = PluginClassLoader.forJar(jar)
        val instances = instantiateExtensions(manifest, classLoader)

        val loaded = LoadedPlugin(manifest, instances, classLoader)
        PluginRegistry.register(loaded)
        wireToRegistries(loaded)
    }

    /** Reload all plugins (creates fresh class loaders). */
    public fun reload(
        runtimeVersion: PluginVersion,
        extraPaths: List<Path> = emptyList(),
    ) {
        PluginRegistry.all().forEach { PluginRegistry.unload(it.manifest.id) }
        load(runtimeVersion, extraPaths)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun readManifestFromJar(jar: File): String? =
        runCatching {
            ZipFile(jar).use { zip ->
                zip.getEntry(MANIFEST_ENTRY)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }
            }
        }.getOrNull()

    internal fun checkVersion(
        manifest: PluginManifest,
        runtimeVersion: PluginVersion,
    ) {
        val range = KumlVersionRange(manifest.kumlVersionRange)
        if (!range.contains(runtimeVersion)) {
            throw VersionMismatchException(
                manifest.id,
                manifest.kumlVersionRange,
                runtimeVersion.toString(),
            )
        }
    }

    private fun instantiateExtensions(
        manifest: PluginManifest,
        classLoader: ClassLoader,
    ): List<KumlPlugin> =
        manifest.extensions.map { ext ->
            try {
                val clazz = classLoader.loadClass(ext.implementation)
                clazz.getDeclaredConstructor().newInstance() as? KumlPlugin
                    ?: throw PluginLoadException(
                        "Class '${ext.implementation}' in plugin '${manifest.id}' does not implement KumlPlugin",
                    )
            } catch (e: ClassNotFoundException) {
                throw PluginLoadException(
                    "Class '${ext.implementation}' not found in plugin '${manifest.id}'",
                    e,
                )
            } catch (e: PluginLoadException) {
                throw e
            } catch (e: Exception) {
                throw PluginLoadException(
                    "Failed to instantiate '${ext.implementation}' in plugin '${manifest.id}'",
                    e,
                )
            }
        }

    private fun wireToRegistries(loaded: LoadedPlugin) {
        for (plugin in loaded.plugins) {
            when (plugin) {
                is KumlThemePlugin -> wireTheme(plugin)
                is KumlLayoutPlugin -> wireLayout(plugin)
                is KumlCodegenPlugin -> wireCodegen(plugin)
                is KumlReversePlugin -> { /* ReverseEngineRegistry has no register() — see KDoc */ }
                is KumlRendererPlugin -> { /* RendererRegistry — deferred to V3.0.29 */ }
            }
        }
    }

    private fun wireTheme(plugin: KumlThemePlugin) {
        plugin.themes().forEach { theme ->
            val capturedTheme: KumlTheme = theme
            ThemeRegistry.register(
                object : KumlThemeProvider {
                    override val name: String = capturedTheme.name

                    override fun theme(): KumlTheme = capturedTheme
                },
            )
        }
    }

    private fun wireLayout(plugin: KumlLayoutPlugin) {
        plugin.engines().forEach { engine ->
            val capturedEngine = engine
            LayoutEngineRegistry.register(
                object : KumlLayoutEngineProvider {
                    override val id: LayoutEngineId = capturedEngine.id

                    override fun engine() = capturedEngine
                },
            )
        }
    }

    private fun wireCodegen(plugin: KumlCodegenPlugin) {
        plugin.generators().forEach { generator ->
            val capturedGenerator = generator
            CodeGenRegistry.register(
                object : KumlCodeGeneratorProvider {
                    override fun generator() = capturedGenerator
                },
            )
        }
    }

    private fun loadBuiltInsViaServiceLoader() {
        java.util.ServiceLoader
            .load(KumlThemeProvider::class.java)
            .forEach { ThemeRegistry.register(it) }
        java.util.ServiceLoader
            .load(KumlLayoutEngineProvider::class.java)
            .forEach { LayoutEngineRegistry.register(it) }
        java.util.ServiceLoader
            .load(KumlCodeGeneratorProvider::class.java)
            .forEach { CodeGenRegistry.register(it) }
    }
}
