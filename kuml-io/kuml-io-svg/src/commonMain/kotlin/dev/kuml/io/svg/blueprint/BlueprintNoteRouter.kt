package dev.kuml.io.svg.blueprint

import kotlin.math.max

/**
 * Routes the anchor lines of a Service Blueprint note to its step cards.
 *
 * The note sits in the column right of the lanes. Every anchor gets its own exit point on the note's left border
 * (at least [EXIT_SPACING] apart), its own vertical in the gutter ([GUTTER_STEP] apart) and then a horizontal track in
 * the margin zone of the card's lane (above or below the cards, never on a card border or a lane boundary) that ends
 * with a short stub into the card. Lane lines are crossed at right angles only, and no two lines share a stretch.
 *
 * Pure geometry, no rendering: tested on its own.
 */
internal object BlueprintNoteRouter {
    /** Distance between the exit points of two anchor lines on the note border. */
    const val EXIT_SPACING: Double = 16.0

    /** Distance between the gutter verticals of two anchor lines. */
    const val GUTTER_STEP: Double = 8.0

    /** Inset of the exit points from the corners of the note. */
    const val CORNER_INSET: Double = 10.0

    private const val MIN_GUTTER: Double = 16.0
    private const val TRACK_TOP_INSET: Double = 4.0
    private val ENTRY_OFFSETS: List<Double> = listOf(0.0, 16.0, -16.0, 32.0, -32.0)

    /** Width of the gutter between lanes and note column for a note with up to [anchors] anchor lines. */
    fun gutterFor(anchors: Int): Double = max(MIN_GUTTER, GUTTER_STEP * anchors + GUTTER_STEP)

    /** Smallest note height that leaves room for [anchors] exit points. */
    fun minNoteHeight(anchors: Int): Double = 2 * CORNER_INSET + EXIT_SPACING * (anchors - 1)

    /** The step card an anchor line ends at (card rectangle, lane-relative margin). */
    class Target(
        val linkId: String,
        val laneTop: Double,
        val cardLeft: Double,
        val cardTop: Double,
        val cardRight: Double,
        val cardBottom: Double,
    ) {
        val cardCx: Double get() = (cardLeft + cardRight) / 2.0
    }

    /**
     * @param noteX left border of the note, [noteY]/[noteH] its vertical extent
     * @param claimed how many lines already end at a lane's track slots / a card: shared across all notes of a diagram
     */
    fun route(
        noteX: Double,
        noteY: Double,
        noteH: Double,
        targets: List<Target>,
        laneSlots: MutableMap<Double, Int>,
        cardEntries: MutableMap<Pair<Double, Double>, Int>,
    ): List<Pair<String, List<Pair<Double, Double>>>> {
        if (targets.isEmpty()) return emptyList()
        val n = targets.size
        val mid = noteY + noteH / 2.0

        // Tracks first (they decide the order of the exit points: top track, top exit).
        class Plan(
            val target: Target,
            val trackY: Double,
            val endX: Double,
            val endY: Double,
        )
        val plans =
            targets.map { t ->
                val slot = laneSlots[t.laneTop] ?: 0
                laneSlots[t.laneTop] = slot + 1
                val top = slot % 2 == 0
                val tier = slot / 2
                val trackY = if (top) t.laneTop + TRACK_TOP_INSET + tier * 2.5 else t.cardBottom + 14.0 - tier * 6.0
                val entry = cardEntries[t.cardLeft to t.laneTop] ?: 0
                cardEntries[t.cardLeft to t.laneTop] = entry + 1
                val endX = t.cardCx + ENTRY_OFFSETS[entry % ENTRY_OFFSETS.size]
                Plan(target = t, trackY = trackY, endX = endX, endY = if (top) t.cardTop else t.cardBottom)
            }
        val ordered = plans.sortedWith(compareBy({ it.trackY }, { it.target.linkId }))
        val exitY = ordered.mapIndexed { k, _ -> mid + (k - (n - 1) / 2.0) * EXIT_SPACING }
        // Gutter verticals: lines that go up give the top exit the slot nearest to the note, lines that go down give
        // it to the bottom exit, so the lines of one direction never cross each other.
        val up = ordered.indices.filter { ordered[it].trackY < exitY[it] }
        val down = ordered.indices.filter { it !in up }
        val slotOf = HashMap<Int, Int>()
        var slot = 0
        for (i in up) slotOf[i] = slot++ // ascending exit y: topmost first = nearest the note
        for (i in down.reversed()) slotOf[i] = slot++
        val out = ArrayList<Pair<String, List<Pair<Double, Double>>>>()
        for ((k, p) in ordered.withIndex()) {
            val gx = noteX - GUTTER_STEP * (slotOf.getValue(k) + 1)
            val y = exitY[k]
            out +=
                p.target.linkId to
                listOf(noteX to y, gx to y, gx to p.trackY, p.endX to p.trackY, p.endX to p.endY)
        }
        return out
    }
}
