package dev.kuml.ai.tools.patch

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process pub/sub broker for cross-session patch notifications.
 *
 * Allows multiple [PatchApplyEngine] instances sharing the same JVM to observe
 * each other's applied patches and conflict signals. Useful for same-machine
 * multi-user collaborative editing scenarios.
 *
 * ## Thread safety
 * [CopyOnWriteArrayList] provides thread-safe iteration during [publish]. Listener
 * exceptions are swallowed via `runCatching` so a misbehaving subscriber cannot
 * break the publish path for other subscribers.
 *
 * ## Backpressure
 * Listeners are called synchronously on the publishing thread. Implementations
 * must not block — offload long-running work to a coroutine or thread pool inside
 * the listener.
 */
public class SharedPatchBroker {
    private val subscribers = CopyOnWriteArrayList<(PatchBrokerEvent) -> Unit>()

    /**
     * Subscribe to broker events. Returns an [AutoCloseable] that removes the
     * listener when closed.
     */
    public fun subscribe(listener: (PatchBrokerEvent) -> Unit): AutoCloseable {
        subscribers.add(listener)
        return AutoCloseable { subscribers.remove(listener) }
    }

    /** Publish [event] to all subscribers. Individual subscriber failures are swallowed. */
    public fun publish(event: PatchBrokerEvent) {
        for (sub in subscribers) {
            runCatching { sub(event) }
        }
    }
}

/** Events published on a [SharedPatchBroker]. */
public sealed interface PatchBrokerEvent {
    /**
     * A patch was successfully applied by one of the connected sessions.
     *
     * @param sessionId  Engine session id that applied the patch.
     * @param ownerId    Owner of the session that applied the patch.
     * @param patchId    ULID of the applied patch.
     * @param elementId  Primary element id touched by the patch.
     */
    public data class PatchAppliedBySession(
        val sessionId: String,
        val ownerId: String,
        val patchId: String,
        val elementId: String,
    ) : PatchBrokerEvent

    /**
     * A conflict was detected: a patch attempted to touch an element already
     * modified within the 5-second conflict window by another patch.
     *
     * @param sessionId           Engine session id that detected the conflict.
     * @param elementId           The element at the center of the conflict.
     * @param conflictingPatchId  The patch id that triggered the conflict.
     */
    public data class ConflictDetected(
        val sessionId: String,
        val elementId: String,
        val conflictingPatchId: String,
    ) : PatchBrokerEvent
}
