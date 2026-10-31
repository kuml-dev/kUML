package dev.kuml.io.svg.blueprint

import kotlin.math.abs
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

    /** Minimum clearance of an anchor track to every horizontal line (lane boundary, card edge). */
    const val TRACK_CLEARANCE: Double = 4.0

    /** Minimum clearance of an anchor track to a lane boundary (Line of Interaction / Visibility / Internal Interaction). */
    const val LANE_LINE_CLEARANCE: Double = 6.0

    /** Minimum distance between two anchor tracks of the whole diagram. */
    const val TRACK_SPACING: Double = 6.0

    /** Raster of the candidate tracks inside a lane's margin zone. */
    const val TRACK_RASTER: Double = 6.0

    /** Distance of the fallback channel below the lowest horizontal line. */
    const val OUTER_CHANNEL_OFFSET: Double = 12.0

    /** Upper bound for candidate tracks examined per anchor (keeps the search bounded). */
    private const val MAX_CANDIDATES: Int = 64
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
        /** Lower lane boundary; defaults to the standard 24 px clearance zone below the card. */
        val laneBottom: Double = cardBottom + 24.0,
    ) {
        val cardCx: Double get() = (cardLeft + cardRight) / 2.0
    }

    /** A horizontal line of the grid an anchor track must keep clear of: [y] between [xFrom] and [xTo]. */
    class HLine(
        val y: Double,
        val xFrom: Double,
        val xTo: Double,
        val clearance: Double = TRACK_CLEARANCE,
    )

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
        horizontalObstacles: List<HLine> = emptyList(),
        claimedTracks: MutableList<Double> = ArrayList(),
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
                val (trackY, top) =
                    pickTrack(
                        t = t,
                        preferTop = slot % 2 == 0,
                        noteX = noteX,
                        obstacles = horizontalObstacles,
                        claimed = claimedTracks,
                    )
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

    private fun freeOf(
        y: Double,
        xLo: Double,
        xHi: Double,
        obstacles: List<HLine>,
        claimed: List<Double>,
    ): Boolean {
        val lo = minOf(xLo, xHi)
        val hi = maxOf(xLo, xHi)
        if (obstacles.any { o -> abs(o.y - y) < o.clearance - EPS && o.xTo >= lo && o.xFrom <= hi }) return false
        return claimed.none { abs(it - y) < TRACK_SPACING - EPS }
    }

    /**
     * Picks the horizontal track of one anchor: the first candidate of a 6 px raster inside the lane's margin zones
     * (above or below the card, preferred side first) that keeps [TRACK_CLEARANCE] to every horizontal line and
     * [TRACK_SPACING] to every track already claimed in the diagram. When no candidate is free, the track runs in an
     * outer channel [OUTER_CHANNEL_OFFSET] below the lowest horizontal line and the line enters the card from below.
     *
     * @return the track y and whether the line enters the card at its top edge
     */
    private fun pickTrack(
        t: Target,
        preferTop: Boolean,
        noteX: Double,
        obstacles: List<HLine>,
        claimed: MutableList<Double>,
    ): Pair<Double, Boolean> {
        val topZone =
            generateSequence(t.laneTop + LANE_LINE_CLEARANCE) { it + TRACK_RASTER }
                .takeWhile { it <= t.cardTop - TRACK_CLEARANCE + EPS }
                .take(MAX_CANDIDATES)
                .toList()
        // Raster counted from the lane boundary upwards: the track nearest to the boundary first.
        val bottomZone =
            generateSequence(t.laneBottom - LANE_LINE_CLEARANCE) { it - TRACK_RASTER }
                .takeWhile { it >= t.cardBottom + TRACK_CLEARANCE - EPS }
                .take(MAX_CANDIDATES)
                .toList()
        val candidates =
            if (preferTop) {
                topZone.map { it to true } + bottomZone.map { it to false }
            } else {
                bottomZone.map { it to false } + topZone.map { it to true }
            }
        for ((y, top) in candidates) {
            if (freeOf(y = y, xLo = t.cardCx - ENTRY_REACH, xHi = noteX, obstacles = obstacles, claimed = claimed)) {
                claimed += y
                return y to top
            }
        }
        var y = (obstacles.maxOfOrNull { it.y } ?: t.laneBottom).coerceAtLeast(t.laneBottom) + OUTER_CHANNEL_OFFSET
        var guard = 0
        while (claimed.any { abs(it - y) < TRACK_SPACING - EPS } && guard++ < MAX_CANDIDATES) y += TRACK_SPACING
        claimed += y
        return y to false
    }

    private const val EPS: Double = 1e-6
    private const val ENTRY_REACH: Double = 32.0
}
