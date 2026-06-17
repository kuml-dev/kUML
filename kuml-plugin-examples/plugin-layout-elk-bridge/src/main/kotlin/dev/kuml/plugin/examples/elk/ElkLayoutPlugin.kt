package dev.kuml.plugin.examples.elk

import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.api.layout.KumlLayoutPlugin

/**
 * ELK Layout Plugin — exposes the built-in ELK layout engine via [KumlLayoutPlugin] SPI.
 *
 * Bridges `kuml-layout-elk` (built-in) to the stable plugin API.
 * This plugin is JVM-only (ELK is Java-only) — not compatible with GraalVM Native Image.
 *
 * Engine ID exposed: `"elk.layered"` (see [ElkLayoutEngine.id]).
 */
public class ElkLayoutPlugin : KumlLayoutPlugin {
    override val descriptor: PluginDescriptor =
        PluginDescriptor(
            id = "dev.kuml.plugin.layout.elk",
            name = "ELK Layout Engine",
            version = PluginVersion(1, 0, 0),
            kumlVersionRange = KumlVersionRange(">=0.12.0"),
            capabilities = setOf(PluginCapability.LAYOUT),
            // No special permissions — layout is pure computation
        )

    override fun engines(): List<KumlLayoutEngine> {
        // Ensure the ELK provider is registered before querying
        val provider = ElkLayoutEngineProvider()
        LayoutEngineRegistry.register(provider)
        return listOf(LayoutEngineRegistry.get(provider.id)!!)
    }
}
