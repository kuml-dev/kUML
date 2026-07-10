package dev.kuml.lsp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-key trailing debouncer. `submit(key, action)` cancels any pending action
 * for that key and (re)schedules `action` to run once `delayMs` after the last
 * submit. Used to coalesce rapid didChange bursts into a single validation run.
 */
class Debouncer(
    private val delayMs: Long,
) : AutoCloseable {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "kuml-lsp-debounce").apply { isDaemon = true }
        }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun submit(
        key: String,
        action: () -> Unit,
    ) {
        pending.remove(key)?.cancel(false)
        pending[key] =
            scheduler.schedule({
                pending.remove(key)
                action()
            }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun cancel(key: String) {
        pending.remove(key)?.cancel(false)
    }

    override fun close() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        scheduler.shutdownNow()
    }
}
