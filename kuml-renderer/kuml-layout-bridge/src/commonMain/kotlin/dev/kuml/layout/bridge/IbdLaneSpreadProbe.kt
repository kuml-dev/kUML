package dev.kuml.layout.bridge

/**
 * Test probe of the IBD lane spread (PAL wave T): [collecting] runs a block with the fix switched on or off and returns
 * one [Snapshot] per IBD layout planned inside it. Outside [collecting] nothing is recorded and the fix is always on.
 * The state is process-wide and meant for tests that run one render after another; production code never sets it.
 */
public object IbdLaneSpreadProbe {
    /** Parallel violations before and after one lane spread run and the ids of the connectors that moved. */
    public data class Snapshot(
        val before: Int,
        val after: Int,
        val changedEdges: List<String>,
    )

    private var sink: MutableList<Snapshot>? = null
    private var enabled: Boolean = true

    internal val spreadEnabled: Boolean get() = enabled

    internal fun record(
        before: Int,
        after: Int,
        changed: List<String>,
    ) {
        sink?.add(Snapshot(before = before, after = after, changedEdges = changed))
    }

    /** Runs [block] and returns its value with the snapshots recorded inside it; [spread] `false` measures without fixing. */
    public fun <T> collecting(
        spread: Boolean,
        block: () -> T,
    ): Pair<T, List<Snapshot>> {
        val previousSink = sink
        val previousEnabled = enabled
        val mine = mutableListOf<Snapshot>()
        sink = mine
        enabled = spread
        try {
            val value = block()
            return value to mine.toList()
        } finally {
            sink = previousSink
            enabled = previousEnabled
        }
    }
}
