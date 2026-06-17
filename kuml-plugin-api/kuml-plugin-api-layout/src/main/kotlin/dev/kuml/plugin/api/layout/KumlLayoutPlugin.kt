package dev.kuml.plugin.api.layout

import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.plugin.api.core.KumlPlugin

/**
 * Plugin SPI for custom kUML layout engines.
 *
 * A layout plugin contributes one or more [KumlLayoutEngine] instances
 * to the [dev.kuml.layout.LayoutEngineRegistry]. Built-in ELK and Grid
 * engines implement this interface.
 *
 * V3.0.27: SPI definition and built-in migration.
 */
public interface KumlLayoutPlugin : KumlPlugin {
    /** All layout engines contributed by this plugin. Must not be empty. */
    public fun engines(): List<KumlLayoutEngine>
}
