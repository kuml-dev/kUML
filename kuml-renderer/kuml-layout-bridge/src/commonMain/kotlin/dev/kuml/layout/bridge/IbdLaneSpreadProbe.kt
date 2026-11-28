package dev.kuml.layout.bridge

/**
 * Test probe of the IBD lane spread (PAL wave T): [collecting] runs a block with the fix switched on or off and returns
 * one [Snapshot] per IBD layout planned inside it. Outside [collecting] nothing is recorded and the fix is always on.
 * The state is per thread (like `PortLabelAudit`): production renders open a scope only to capture a run, and a render on another
 * thread is never affected by it.
 */
public object IbdLaneSpreadProbe {
    /** Parallel violations before and after one lane spread run and the ids of the connectors that moved. */
    public data class Snapshot(
        val before: Int,
        val after: Int,
        val changedEdges: List<String>,
    )

    private class State(
        val sink: MutableList<Snapshot>,
        val enabled: Boolean,
    )

    private val slot = ThreadSlot<State>()

    internal val spreadEnabled: Boolean get() = slot.get()?.enabled ?: true

    internal fun record(
        before: Int,
        after: Int,
        changed: List<String>,
    ) {
        slot.get()?.sink?.add(Snapshot(before = before, after = after, changedEdges = changed))
    }

    internal fun replay(snapshots: List<Snapshot>) {
        slot.get()?.sink?.addAll(snapshots)
    }

    /** Runs [block] and returns its value with the snapshots recorded inside it; [spread] `false` measures without fixing. */
    public fun <T> collecting(
        spread: Boolean,
        block: () -> T,
    ): Pair<T, List<Snapshot>> {
        val previous = slot.get()
        val mine = mutableListOf<Snapshot>()
        slot.set(State(sink = mine, enabled = spread))
        try {
            val value = block()
            return value to mine.toList()
        } finally {
            slot.set(previous)
        }
    }
}
