package dev.kuml.layout.bridge

/**
 * Probe of the IBD note room gate (PAL Welle Y4): [collecting] runs a block with the gate switched on or off (or forced to
 * take the second layout) and returns one [Decision] per IBD rendered inside it. Outside [collecting] nothing is recorded
 * and the gate is on. The state is per thread (like `PortLabelAudit`), so a [collecting] scope never changes a render on another thread; production
 * code never sets it. A decision holds counts and ids only, never a note body.
 */
public object IbdNoteRoomProbe {
    /** What the gate saw for one diagram: note misses and drawing area of both runs, and which run it kept. */
    public data class Decision(
        val diagram: String,
        val reservedNoteIds: List<String>,
        val missesA: Int,
        val missesB: Int,
        val areaA: Double,
        val areaB: Double,
        val takenB: Boolean,
        val reason: String,
    )

    private class State(
        val sink: MutableList<Decision>,
        val gateEnabled: Boolean,
        val forceSecond: Boolean,
    )

    private val slot = ThreadSlot<State>()

    /** `false` inside a [collecting] call with `gate = false`: the renderer then never asks for a second layout. */
    public val disabled: Boolean get() = !(slot.get()?.gateEnabled ?: true)

    /** `true` inside a [collecting] call with `forceSecond = true`: the second run is kept whatever it measures (tests only). */
    public val forced: Boolean get() = slot.get()?.forceSecond ?: false

    public fun record(decision: Decision) {
        slot.get()?.sink?.add(decision)
    }

    /** Runs [block] and returns its value with the decisions recorded inside it. */
    public fun <T> collecting(
        gate: Boolean = true,
        forceSecond: Boolean = false,
        block: () -> T,
    ): Pair<T, List<Decision>> {
        val previous = slot.get()
        val mine = mutableListOf<Decision>()
        slot.set(State(sink = mine, gateEnabled = gate, forceSecond = forceSecond))
        try {
            val value = block()
            return value to mine.toList()
        } finally {
            slot.set(previous)
        }
    }
}
