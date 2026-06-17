package dev.kuml.plugin.loader.loader

import dev.kuml.plugin.api.core.KumlPlugin
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Per-plugin class loader for external plugin JARs.
 *
 * The parent is the class loader that loaded [KumlPlugin]
 * (the plugin-api layer), NOT the application class loader. This prevents
 * plugin dependencies from leaking into the host application or into other plugins.
 */
public class PluginClassLoader(
    jarUrls: Array<URL>,
    parent: ClassLoader = KumlPlugin::class.java.classLoader,
) : URLClassLoader(jarUrls, parent) {
    public companion object {
        public fun forJar(jarPath: File): PluginClassLoader = PluginClassLoader(arrayOf(jarPath.toURI().toURL()))
    }
}
