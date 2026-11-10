package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.bottom
import dev.kuml.layout.bridge.note.left
import dev.kuml.layout.bridge.note.rectOf
import dev.kuml.layout.bridge.note.right
import dev.kuml.layout.bridge.note.top
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps an edge label inside the subject frame of its line (Nacharbeit 54, use case `extend` labels): the region the
 * label may take is the frame deflated by [MARGIN_PX] (2 px border halo plus 2 px clearance), so a label inside it
 * never touches the border. [candidatesFor] offers spots beside every segment of the line, already clamped into the
 * region; [avoidFor] turns the outside of the region into hard obstacles; [guard] clamps a chosen label as the last
 * line of defence ("never crosses a frame border" then holds by construction).
 */
internal object FrameKeeper {
    const val MARGIN_PX = 4f
    private val OFFSETS = listOf(4f, 8f, 12f)
    private val FRACTIONS = listOf(0.5f, 0.25f, 0.75f)
    private const val HUGE_PX = 100_000f
    private const val EPS = 0.01f

    /** The region a label of the line may take inside [frame]. */
    fun allowedRegion(frame: Rect): Rect =
        rectOf(
            left = frame.left + MARGIN_PX,
            top = frame.top + MARGIN_PX,
            right = frame.right - MARGIN_PX,
            bottom = frame.bottom - MARGIN_PX,
        )

    /** The four half planes outside [allowed]: a label that touches one of them leaves the region. */
    fun avoidFor(allowed: Rect): List<Rect> =
        listOf(
            rectOf(left = allowed.left - HUGE_PX, top = allowed.top - HUGE_PX, right = allowed.left, bottom = allowed.bottom + HUGE_PX),
            rectOf(left = allowed.right, top = allowed.top - HUGE_PX, right = allowed.right + HUGE_PX, bottom = allowed.bottom + HUGE_PX),
            rectOf(left = allowed.left, top = allowed.top - HUGE_PX, right = allowed.right, bottom = allowed.top),
            rectOf(left = allowed.left, top = allowed.bottom, right = allowed.right, bottom = allowed.bottom + HUGE_PX),
        )

    /** True when [r] lies fully inside [allowed]. */
    fun inside(
        r: Rect,
        allowed: Rect,
    ): Boolean =
        r.left >= allowed.left - EPS && r.right <= allowed.right + EPS && r.top >= allowed.top - EPS && r.bottom <= allowed.bottom + EPS

    /** [r] moved (not resized) into [allowed]; a rectangle wider or taller than the region is aligned to its left/top edge. */
    fun clamp(
        r: Rect,
        allowed: Rect,
    ): Rect {
        val w = r.size.width
        val h = r.size.height
        val x = max(allowed.left, min(r.left, allowed.right - w))
        val y = max(allowed.top, min(r.top, allowed.bottom - h))
        return Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))
    }

    /** True when a label of [size] can lie inside [allowed] at all. */
    fun fits(
        size: Size,
        allowed: Rect,
    ): Boolean = size.width <= allowed.size.width + EPS && size.height <= allowed.size.height + EPS

    /**
     * Candidate spots of a label of [size] along [polyline], all inside [allowed]: beside every vertical segment (left
     * and right), above and below every horizontal one, offsets 4 / 8 / 12 px, at the middle and the quarter points;
     * then the label centred on the line. Duplicates after clamping are dropped.
     */
    fun candidatesFor(
        polyline: List<Point>,
        size: Size,
        allowed: Rect,
    ): List<Rect> {
        val out = LinkedHashSet<Rect>()

        fun add(
            x: Float,
            y: Float,
        ) {
            out += clamp(r = Rect(origin = Point(x = x, y = y), size = size), allowed = allowed)
        }
        val w = size.width
        val h = size.height
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val vertical = abs(a.x - b.x) < EPS && abs(a.y - b.y) >= EPS
            val horizontal = abs(a.y - b.y) < EPS && abs(a.x - b.x) >= EPS
            for (t in FRACTIONS) {
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                for (off in OFFSETS) {
                    if (vertical) {
                        add(x = a.x + off, y = py - h / 2f)
                        add(x = a.x - off - w, y = py - h / 2f)
                    } else if (horizontal) {
                        add(x = px - w / 2f, y = a.y + off)
                        add(x = px - w / 2f, y = a.y - off - h)
                    }
                }
            }
        }
        // the label centred on the line, clamped into the region (the placer then paints the halo behind it)
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            add(x = (a.x + b.x) / 2f - w / 2f, y = (a.y + b.y) / 2f - h / 2f)
        }
        return out.toList()
    }
}
