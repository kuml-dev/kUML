package dev.kuml.ai.tools.patch.compliance

/**
 * Non-suspending compliance event sink.
 *
 * Unlike [dev.kuml.ai.tools.patch.aitrace.AiTraceSink] (which is a `suspend fun`
 * interface designed for coroutine dispatch), [ComplianceSink] is intentionally
 * non-suspending so it can be called from both suspending and non-suspending
 * contexts without a coroutine scope.
 *
 * Implementations must be thread-safe — [emit] may be called concurrently from
 * multiple coroutine dispatchers.
 */
public interface ComplianceSink {
    /** Emit a compliance event. Must not throw; swallow or log errors internally. */
    public fun emit(event: ComplianceEvent)
}

/** No-op sink — default for paths that do not need compliance logging. */
public object NoopComplianceSink : ComplianceSink {
    override fun emit(event: ComplianceEvent): Unit = Unit
}

/**
 * In-memory recording sink for test assertions.
 *
 * Thread-safe via `synchronized(events)`.
 */
public class InMemoryComplianceSink : ComplianceSink {
    private val events = mutableListOf<ComplianceEvent>()

    override fun emit(event: ComplianceEvent) {
        synchronized(events) { events += event }
    }

    /** Returns a snapshot of all emitted events in emission order. */
    public fun snapshot(): List<ComplianceEvent> = synchronized(events) { events.toList() }

    /** Clears all recorded events. */
    public fun clear(): Unit = synchronized(events) { events.clear() }
}
