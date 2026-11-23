package dev.kuml.layout.bridge

/** PAL Welle D2 (12d): minimum clearance (px) between a found/lost origin circle and any lifeline axis. */
public const val SEQ_FOUND_LOST_FOREIGN_CLEARANCE: Float = 16f

/** PAL Welle D2 (12d): a found/lost origin sits at least this far (px) outside the outermost lifeline axis. */
public const val SEQ_FOUND_LOST_EDGE_MARGIN: Float = 40f

/**
 * PAL Welle D2 (12d): x of the circle that marks the unknown sender (found) or receiver (lost) of a found/lost message.
 *
 * A found message always comes from the left of the whole diagram and a lost message always leaves to the right, so the
 * circle never sits next to a neighbouring lifeline (before, the side depended on whether the target was the outermost
 * lifeline and the circle landed beside the next lifeline). The arrow is at least [length] long and the circle is at
 * least [SEQ_FOUND_LOST_EDGE_MARGIN] outside the outermost lifeline axis, hence always at least
 * [SEQ_FOUND_LOST_FOREIGN_CLEARANCE] from every lifeline axis.
 *
 * @param isFound `true` for a found message (origin left), `false` for a lost message (origin right).
 * @param targetCx axis x of the lifeline the message belongs to.
 * @param length arrow length (px) needed for the label.
 * @param firstLifelineX axis x of the leftmost lifeline.
 * @param lastLifelineX axis x of the rightmost lifeline.
 */
public fun seqFoundLostOriginX(
    isFound: Boolean,
    targetCx: Float,
    length: Float,
    firstLifelineX: Float,
    lastLifelineX: Float,
): Float =
    if (isFound) {
        minOf(targetCx - length, firstLifelineX - SEQ_FOUND_LOST_EDGE_MARGIN)
    } else {
        maxOf(targetCx + length, lastLifelineX + SEQ_FOUND_LOST_EDGE_MARGIN)
    }

/** Radius of the found/lost circle plus a little canvas padding, reserved by the layout beyond the circle centre. */
internal const val SEQ_FOUND_LOST_CIRCLE_RESERVE: Float = 5f + 8f

/** Arrow length used by the renderer: at least 120 px and long enough for the label (plus 24 px). */
public fun seqFoundLostLength(label: String): Float = maxOf(120f, label.length * 6.5f + 24f)
