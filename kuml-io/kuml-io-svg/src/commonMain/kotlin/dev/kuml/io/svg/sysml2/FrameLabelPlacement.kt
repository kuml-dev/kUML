package dev.kuml.io.svg.sysml2

import dev.kuml.layout.Point
import dev.kuml.layout.Rect

/** Result of [placeFrameLabel]: the left [x] of the label and whether no free spot existed. */
internal data class FrameLabelPlacement(
    val x: Float,
    val fallbackOccluded: Boolean,
)

/**
 * Places a frame label (requirement-group tab, use-case subject title, swimlane name) of width [labelW]
 * inside the top band `[frame.y, frame.y + bandH]` of [frame] so that no edge of [edges] (polylines in
 * canvas coordinates) crosses it. Order: flush left, then the first gap to the right (scanning from
 * `labelW + 12` in 8 px steps), then flush right, otherwise flush left with `fallbackOccluded = true`.
 */
internal fun placeFrameLabel(
    frame: Rect,
    labelW: Float,
    bandH: Float,
    edges: List<List<Point>>,
    inset: Float = 0f,
    safety: Float = 6f,
): FrameLabelPlacement {
    val left = frame.origin.x + inset
    val right = frame.origin.x + frame.size.width - inset
    val bandTop = frame.origin.y - safety
    val bandBottom = frame.origin.y + bandH + safety

    fun free(x: Float): Boolean {
        val r =
            Rect(
                origin = Point(x = x - safety, y = bandTop),
                size = dev.kuml.layout.Size(width = labelW + 2 * safety, height = bandBottom - bandTop),
            )
        for (pl in edges) {
            for (i in 0 until pl.size - 1) if (Sysml2LabelPlacement.segIntersects(p = pl[i], q = pl[i + 1], r = r)) return false
        }
        return true
    }

    if (labelW >= right - left) return FrameLabelPlacement(x = left, fallbackOccluded = !free(left))
    if (free(left)) return FrameLabelPlacement(x = left, fallbackOccluded = false)
    var x = left + labelW + 12f
    var guard = 0
    while (x + labelW <= right && guard++ < 1000) {
        if (free(x)) return FrameLabelPlacement(x = x, fallbackOccluded = false)
        x += 8f
    }
    val rx = right - labelW
    if (free(rx)) return FrameLabelPlacement(x = rx, fallbackOccluded = false)
    return FrameLabelPlacement(x = left, fallbackOccluded = true)
}
