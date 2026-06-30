package dev.kuml.io.svg

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
    val inset = 2f
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

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
