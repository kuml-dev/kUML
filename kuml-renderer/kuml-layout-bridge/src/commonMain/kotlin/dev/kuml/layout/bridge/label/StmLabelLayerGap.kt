package dev.kuml.layout.bridge.label

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.layout.LAYOUT_SPACING_SCALE
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.edge.StmEdgeAdapter

/**
 * Room between two layers of a state machine that its transition labels need (wave F). ELK is not told about the
 * labels (they are placed after the layout, from the final routes), so with a long label such as
 * `T21 [guard] / effect` and a left-to-right flow the default layer gap was narrower than the label and the label
 * ended up on a state. The label extent along the flow direction (width for left/right, height for up/down) plus
 * [CLEARANCE_PX] on each side is therefore requested as the layer gap. It is a gap for the whole diagram, not for one
 * pair of states: ELK's layered engine has no per-gap spacing.
 */
public object StmLabelLayerGap {
    /** Free room kept on each side of the widest label inside the gap (px). */
    public const val CLEARANCE_PX: Float = 8f

    /** The engine default of the layer gap in base px (`ElkEngineConfiguration.layerSpacing`); smaller requests are not raised. */
    public const val ENGINE_DEFAULT_BASE_PX: Float = 90f

    /**
     * Largest extent of an STM transition label along the flow direction of [diagram], in drawn px, or `0` when no
     * visible transition carries a label. Uses the same wrap and box size as the renderer.
     */
    public fun maxLabelExtent(
        model: Sysml2Model,
        diagram: StmDiagram,
    ): Float {
        val maxWidth =
            diagram.layout
                ?.labelMaxWidth
                ?.coerceIn(LayoutLimits.LABEL_MAX_WIDTH_MIN, LayoutLimits.LABEL_MAX_WIDTH_MAX)
                ?.toFloat() ?: StmLabelWrap.DEFAULT_MAX_WIDTH_PX
        val alongWidth = diagram.layout?.direction.let { it == LayoutFlow.Right || it == LayoutFlow.Left }
        val adapter = StmEdgeAdapter(model = model, diagram = diagram)
        var extent = 0f
        for (usage in model.usages) {
            val parts = adapter.metadataFor(usage.id)?.labelParts.orEmpty()
            if (parts.isEmpty()) continue
            val box = StmLabelWrap.boxSize(lines = StmLabelWrap.wrap(parts = parts, maxWidthPx = maxWidth))
            extent = maxOf(extent, if (alongWidth) box.width else box.height)
        }
        return extent
    }

    /**
     * The layer gap in base px (before the engine's [LAYOUT_SPACING_SCALE]) that holds the widest label with
     * [CLEARANCE_PX] on both sides, or `null` when the engine default is already enough.
     */
    public fun requiredLayerGapBase(
        model: Sysml2Model,
        diagram: StmDiagram,
    ): Float? {
        val extent = maxLabelExtent(model = model, diagram = diagram)
        if (extent <= 0f) return null
        val base = (extent + 2f * CLEARANCE_PX) / LAYOUT_SPACING_SCALE
        return base.takeIf { it > ENGINE_DEFAULT_BASE_PX }
    }
}
