package dev.kuml.plugin.api.renderer

import dev.kuml.core.model.KumlModel
import dev.kuml.plugin.api.core.KumlPlugin

/**
 * Plugin SPI for custom kUML output renderers.
 *
 * Renderers produce a new output format (PDF, DOT, Excalidraw JSON, …)
 * from a [KumlModel]. Built-in SVG and PNG renderers are NOT exposed
 * as plugins (they live in `kuml-io-svg` / `kuml-io-png`); this SPI
 * targets third-party / community-built renderers.
 *
 * V3.0.27: SPI definition. No built-in implementations yet — reference
 * implementations come in V3.0.32 (PDF-Renderer).
 */
public interface KumlRendererPlugin : KumlPlugin {
    /** Describes which diagram types and output formats this renderer supports. */
    public val renderCapabilities: RendererCapabilities

    /**
     * Render [model] to [format] and return the raw bytes.
     *
     * @param model  the kUML model to render
     * @param format one of the formats declared in [renderCapabilities]
     * @param options renderer-specific key/value options
     * @throws IllegalArgumentException if [format] is not supported
     */
    public fun render(
        model: KumlModel,
        format: String,
        options: Map<String, String> = emptyMap(),
    ): ByteArray
}
