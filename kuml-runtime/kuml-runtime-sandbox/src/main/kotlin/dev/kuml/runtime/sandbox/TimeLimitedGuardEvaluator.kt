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
                executor.submit(Callable { delegate.evaluate(guard, instance, event) })
            } catch (ex: RejectedExecutionException) {
                return GuardResult.Failed("Sandbox executor rejected task: ${ex.message}")
            }

        return try {
            future.get(policy.guardTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (ex: TimeoutException) {
            future.cancel(true)
            GuardResult.Failed("Guard timed out after ${policy.guardTimeoutMs} ms")
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
