package dev.kuml.ai.tools.patch.aitrace

import dev.kuml.runtime.AiTraceEntry

/**
 * Dependency-injection seam for AI patch lifecycle events.
 *
 * Production code routes this to the existing `kuml-runtime-trace` OTLP exporter.
 * CLI / test paths use [NoopAiTraceSink].
 *
 * Implementations must be coroutine-safe — engine calls may happen on
 * arbitrary dispatchers.
 */
public interface AiTraceSink {
    public suspend fun emit(entry: AiTraceEntry)
}

/** No-op sink — used in tests and CLI paths that do not need trace output. */
public object NoopAiTraceSink : AiTraceSink {
    override suspend fun emit(entry: AiTraceEntry): Unit = Unit
}

/**
 * In-memory recording sink for test assertions.
 *
 * Thread-safe via `synchronized(collected)` — safe to use from coroutines on
 * any dispatcher.
 */
public class InMemoryAiTraceSink : AiTraceSink {
    private val collected = mutableListOf<AiTraceEntry>()

    override suspend fun emit(entry: AiTraceEntry) {
        synchronized(collected) { collected += entry }
    }

    /** Returns a snapshot of all emitted entries in emission order. */
    public fun snapshot(): List<AiTraceEntry> = synchronized(collected) { collected.toList() }

    /** Clears all recorded entries. */
    public fun clear(): Unit = synchronized(collected) { collected.clear() }
}
