package dev.kuml.layout.bridge

import dev.kuml.layout.bridge.label.PortLabelAudit

/**
 * The recorders an IBD render run writes to (the port label audit and the lane spread probe), captured per run (PAL Welle Y4).
 * The note room gate renders a diagram twice and keeps one run; a surface that observes the recorders (tests, the audit)
 * must see only the kept run, so each run is captured and only the kept one is replayed into the enclosing scopes. Outside
 * any enclosing scope nothing is recorded, as before, and [replay] does nothing.
 */
public object IbdRenderRecorders {
    /** The value of a captured run and what it recorded. */
    public class Captured<T> internal constructor(
        public val value: T,
        internal val audit: List<PortLabelAudit.Snapshot>,
        internal val laneSpread: List<IbdLaneSpreadProbe.Snapshot>,
    )

    /** Runs [block] with its own recorder scopes (keeping the enclosing repair and spread settings) and captures what it recorded. */
    public fun <T> capture(block: () -> T): Captured<T> {
        val (inner, audit) =
            PortLabelAudit.collecting(repair = PortLabelAudit.repairEnabled) {
                IbdLaneSpreadProbe.collecting(spread = IbdLaneSpreadProbe.spreadEnabled, block = block)
            }
        return Captured(value = inner.first, audit = audit, laneSpread = inner.second)
    }

    /** Hands what [captured] recorded to the enclosing scopes. */
    public fun replay(captured: Captured<*>) {
        PortLabelAudit.replay(captured.audit)
        IbdLaneSpreadProbe.replay(captured.laneSpread)
    }
}
