package dev.kuml.plugin.api.renderer

/**
 * Describes what a [KumlRendererPlugin] can render.
 *
 * @param supportedFormats e.g. `setOf("pdf", "dot", "excalidraw")`
 * @param supportedDiagramTypes kUML diagram-type keys, e.g. `"uml-class"`, `"c4-container"`, `"*"` for all
 */
public data class RendererCapabilities(
    val supportedFormats: Set<String>,
    val supportedDiagramTypes: Set<String>,
) {
    public fun canRender(
        diagramType: String,
        format: String,
    ): Boolean =
        (supportedDiagramTypes.contains("*") || supportedDiagramTypes.contains(diagramType)) &&
            supportedFormats.contains(format)
}
