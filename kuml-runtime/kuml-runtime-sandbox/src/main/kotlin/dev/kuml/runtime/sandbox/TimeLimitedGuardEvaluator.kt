package dev.kuml.runtime.sandbox

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Decorates a [GuardEvaluator] with a configurable timeout enforced via
 * [java.util.concurrent].
 *
 * Guards that exceed [SandboxPolicy.guardTimeoutMs] are cancelled and
 * return [GuardResult.Failed]. The underlying thread pool uses daemon
 * threads so it never prevents JVM shutdown.
 *
 * Implements [AutoCloseable] — call [close] when the evaluator is no
 * longer needed to release the thread pool immediately. Not calling
 * [close] is safe (daemon threads expire), but wastes resources.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public class TimeLimitedGuardEvaluator(
    private val delegate: GuardEvaluator,
    private val policy: SandboxPolicy,
    private val executor: ExecutorService = defaultExecutor(),
) : GuardEvaluator,
    AutoCloseable {
    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult {
        if (guard.isNullOrBlank()) return GuardResult.True

        val future =
            try {
                executor.submit(Callable { delegate.evaluate(guard = guard, instance = instance, event = event) })
            } catch (ex: RejectedExecutionException) {
                return GuardResult.Failed("Sandbox executor rejected task: ${ex.message}")
            }

        return try {
            future.get(policy.guardTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (ex: TimeoutException) {
            future.cancel(true)
            GuardResult.Failed("$TIMEOUT_MESSAGE_PREFIX${policy.guardTimeoutMs} ms")
        } catch (ex: java.util.concurrent.ExecutionException) {
            GuardResult.Failed("Guard threw: ${ex.cause?.message ?: ex.message}")
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            GuardResult.Failed("Guard evaluation was interrupted")
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    public companion object {
        /**
         * Prefix of the [GuardResult.Failed] message produced when a guard is cancelled for
         * exceeding [SandboxPolicy.guardTimeoutMs] (see [evaluate]'s `TimeoutException` branch).
         *
         * Public so callers that need to distinguish a sandbox timeout from any other guard
         * failure (e.g. `dev.kuml.desktop.simulation.SimulationSession.statusFor`, which maps a
         * timeout to a distinct `GuardTimeout` status) can match against this constant instead of
         * duplicating the literal — a `message.contains("timed out")` at the call site would
         * silently degrade to "just another GuardFailed" the next time this string is reworded,
         * with no test anywhere failing to catch the drift.
         */
        public const val TIMEOUT_MESSAGE_PREFIX: String = "Guard timed out after "

        /**
         * Creates a cached thread pool with daemon threads named
         * `kuml-sandbox-guard-<N>`.
         */
        public fun defaultExecutor(): ExecutorService {
            val tid = AtomicLong(0)
            return Executors.newCachedThreadPool { r ->
                Thread(r, "kuml-sandbox-guard-${tid.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
