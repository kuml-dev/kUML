package dev.kuml.io.svg

// Margin between the outer diagram frame and the true SVG canvas edge.
// internal (not private): renderUmlStateDiagram's SM-frame is the one other
// place in the renderer that draws a canvas-covering "diagram frame" (as a
// layout group rather than through renderDiagramFrame below) and needs the
// exact same edge clearance so it, too, reaches all the way down to the
// watermark band added to canvasH.
internal const val DIAGRAM_FRAME_INSET_PX = 2f

/**
 * Renders the outer UML diagram frame: a rounded rectangle covering the SVG canvas
 * and a small `typeLabel: name` label in the top-left corner (within the padding margin).
 *
 * Must be the FIRST write to [builder] so the frame sits under all diagram content (z-order).
 *
 * Skips rendering when [name] is blank.
 */
internal fun renderDiagramFrame(
    typeLabel: String,
    name: String,
    canvasW: Float,
    canvasH: Float,
    builder: SvgBuilder,
) {
    if (name.isBlank()) return
    val inset = DIAGRAM_FRAME_INSET_PX
    val x = inset
    val y = inset
    val w = canvasW - 2f * inset
    val h = canvasH - 2f * inset

    builder.tag("g", mapOf("id" to "kuml-diagram-frame")) {
        // Outer rounded rectangle
        tag(
            "rect",
            mapOf(
                "x" to fmt(x),
                "y" to fmt(y),
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to "6",
                "ry" to "6",
                "class" to "kuml-frame",
            ),
        )
        // Type label (small, muted) + name (bold) in top-left
        tag(
            "text",
            mapOf("class" to "kuml-small", "x" to "8", "y" to "13"),
        ) { text("$typeLabel: $name") }
    }
}

private fun fmt(v: Float): String = fmt2(v)
