package dev.kuml.ai.tools.patch.fakes

import dev.kuml.ai.tools.patch.aitrace.AiTraceSink
import dev.kuml.runtime.AiTraceEntry
import io.kotest.matchers.shouldNotBe

/**
 * Test-friendly [AiTraceSink] that records all emitted entries for assertions.
 */
class FakeAiTraceSink : AiTraceSink {
    private val collected = mutableListOf<AiTraceEntry>()

    override suspend fun emit(entry: AiTraceEntry) {
        synchronized(collected) { collected += entry }
    }

    /** Returns all emitted entries in emission order. */
    fun snapshot(): List<AiTraceEntry> = synchronized(collected) { collected.toList() }

    /** Returns all emitted entries of type [T]. */
    inline fun <reified T : AiTraceEntry> entriesOf(): List<T> = snapshot().filterIsInstance<T>()

    /** Asserts that at least one entry of type [T] was emitted and returns the first. */
    inline fun <reified T : AiTraceEntry> expectEntry(): T {
        val entry = entriesOf<T>().firstOrNull()
        entry shouldNotBe null
        return entry!!
    }

    /** Returns true if at least one entry of type [T] was emitted. */
    inline fun <reified T : AiTraceEntry> hasEntry(): Boolean = entriesOf<T>().isNotEmpty()
}
