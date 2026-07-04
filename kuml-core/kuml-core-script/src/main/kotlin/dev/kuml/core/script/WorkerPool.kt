package dev.kuml.core.script

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A **warm-worker pool** of pre-started script-evaluation child JVMs (Welle 3 of
 * the MCP-Sandbox architecture).
 *
 * ## Problem it solves
 *
 * Welle 2's [ChildProcessScriptEvaluator] launches a brand-new JVM per request.
 * Measured cold-start overhead was ~1.5 s per call (JVM boot + Kotlin-compiler
 * warm-up) — fine for a one-off CLI render, unacceptable for interactive MCP use.
 *
 * ## How "warm" works — "use-once + recycle", NOT reuse
 *
 * The pool keeps [poolSize] [WarmScriptWorker] processes that have already
 * booted **and fully initialised the Kotlin scripting host**, then parked
 * themselves blocked on stdin ([ScriptWorkerMain] warm mode, signalled via a
 * ready line — the parent never guesses readiness from a fixed sleep). An
 * incoming [evaluate] hands the request to one such already-warm worker, so the
 * expensive warm-up is *off* the critical path.
 *
 * Each worker still serves **exactly one** request and then exits — there is no
 * reuse of a process across scripts. This is deliberate: it removes any chance
 * of compiler-/scripting-host-internal state (or [dev.kuml.c4.dsl.C4Ids]
 * counters, System properties, etc.) leaking from one untrusted script into the
 * next. "Warm" means *pre-booted*, not *multi-use*. After a worker is consumed
 * the pool immediately launches a replacement in the background to refill.
 *
 * ## Backpressure policy (design decision)
 *
 * When a request arrives and no worker is idle (all busy, replacements still
 * warming), the pool uses a **bounded wait + capped cold-start fallback**:
 *
 *  1. Wait up to [checkoutTimeoutMillis] for a warming replacement to become
 *     idle. This is the common burst case (a replacement is on its way) and
 *     keeps latency close to the warm-hit path without spawning anything extra.
 *  2. If no worker frees up in time **and** the total number of live worker
 *     processes is below the hard ceiling [maxConcurrentWorkers], launch a
 *     single extra warm worker for this request (behaves like a Welle-2
 *     cold-start for that one call). This bounds tail latency under sustained
 *     load without unbounded process growth.
 *  3. If the ceiling is already reached, the request **fails fast** with a
 *     [FailureKind.SANDBOX] "pool saturated" error rather than either blocking
 *     forever or spawning yet another JVM.
 *
 * **Why a hard ceiling matters (security, not just resource hygiene):** without
 * a cap, a burst of concurrent hostile requests could make the pool spawn an
 * unbounded number of JVMs — a fork-bomb-style DoS *against the host*, which is
 * itself one of the attack goals in the threat model. [maxConcurrentWorkers] is
 * the fork-bomb guard: at most that many script JVMs can ever be alive at once,
 * regardless of load. The tradeoff (rejecting requests at saturation vs. letting
 * an attacker exhaust host memory) is resolved firmly in favour of the host.
 *
 * ## Fail-closed
 *
 * Consistent with Welle 2: if the pool can build **no** working worker at all
 * (child launches systematically fail — e.g. no usable `java` binary), requests
 * surface a [FailureKind.SANDBOX] failure. There is **never** a silent fallback
 * to in-process evaluation of untrusted code.
 *
 * V0.23.3 — Welle 3.
 */
internal class WorkerPool(
    private val poolSize: Int = defaultPoolSize(),
    private val maxConcurrentWorkers: Int = defaultMaxConcurrent(defaultPoolSize()),
    private val checkoutTimeoutMillis: Long = DEFAULT_CHECKOUT_TIMEOUT_MILLIS,
    private val readyTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
    private val timeoutSeconds: Long = ChildProcessScriptEvaluator.DEFAULT_TIMEOUT_SECONDS,
    private val maxHeapMb: Int = ChildProcessScriptEvaluator.DEFAULT_MAX_HEAP_MB,
    private val javaBinary: String = WorkerProcessSupport.defaultJavaBinary(),
    private val classpath: String = System.getProperty("java.class.path") ?: "",
    /** Log sink; defaults to stderr so it never corrupts the MCP stdout protocol. */
    private val log: (String) -> Unit = { msg -> System.err.println("[kuml-worker-pool] $msg") },
) : AutoCloseable {
    /** Idle, ready-to-assign workers. */
    private val idle = java.util.concurrent.ConcurrentLinkedQueue<WarmScriptWorker>()

    /** Every live worker (starting, idle, or busy) — the ceiling is enforced against this. */
    private val live = ConcurrentHashMap.newKeySet<WarmScriptWorker>()

    /** Count of workers currently starting (launched, not yet ready). Metric only. */
    private val startingCount = AtomicInteger(0)

    private val closed = AtomicBoolean(false)

    /** Single-threaded background refiller so replacement launches never block callers. */
    private val refiller =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "kuml-worker-pool-refiller").apply { isDaemon = true }
        }

    init {
        require(poolSize >= 1) { "poolSize must be >= 1" }
        require(maxConcurrentWorkers >= poolSize) { "maxConcurrentWorkers must be >= poolSize" }
        // Prime the pool in the background so construction does not block startup.
        refiller.execute { refillToTarget() }
    }

    /**
     * Evaluates [source] on a warm worker, applying the backpressure policy when
     * none is immediately idle. Never throws for ordinary script problems; every
     * outcome is an [EvaluatedScript].
     */
    fun evaluate(
        source: String,
        fileName: String = "script.kuml.kts",
    ): EvaluatedScript {
        if (closed.get()) {
            return EvaluatedScript.Failure(FailureKind.SANDBOX, "Script sandbox pool is shut down.")
        }

        // Layer 1 guard in the parent, before spending a worker on a hostile
        // script (defence in depth; the worker re-runs it too).
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(FailureKind.GUARD, e.message ?: "kUML script rejected by security guard.")
        }

        val worker =
            checkoutIdleWorker()
                ?: waitForWorker()
                ?: spawnFallbackWorker()
                ?: return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Script sandbox pool saturated: no worker available and the concurrent-worker ceiling " +
                        "($maxConcurrentWorkers) is reached. Retry shortly.",
                )

        logState("assigned worker pid=${worker.pid()}")
        return try {
            worker.evaluate(source, fileName)
        } finally {
            retire(worker)
            // A slot just freed up (worker consumed) — refill in the background.
            scheduleRefill()
        }
    }

    /** Pops an idle worker and claims it, skipping any that died while parked. */
    private fun checkoutIdleWorker(): WarmScriptWorker? {
        while (true) {
            val w = idle.poll() ?: return null
            if (w.isDead) {
                retire(w)
                continue
            }
            if (w.tryClaim()) return w
            // Lost the race / it died between poll and claim — drop it.
            retire(w)
        }
    }

    /**
     * Waits up to [checkoutTimeoutMillis] for a warming replacement to become
     * idle, polling the idle queue. Returns a claimed worker or null on timeout.
     */
    private fun waitForWorker(): WarmScriptWorker? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(checkoutTimeoutMillis)
        // Nudge the refiller in case the pool is below target.
        scheduleRefill()
        while (System.nanoTime() < deadline) {
            checkoutIdleWorker()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return checkoutIdleWorker()
    }

    /**
     * Backpressure step 2: launch one extra warm worker for this request, but
     * only if the hard concurrency ceiling permits. Returns a claimed worker or
     * null if the ceiling is reached (→ caller fails fast).
     */
    private fun spawnFallbackWorker(): WarmScriptWorker? {
        if (live.size >= maxConcurrentWorkers) return null
        // Optimistic launch, then re-check the ceiling: newWorker() adds to the
        // live set, so if a concurrent caller pushed us over, we back out and
        // give the slot back. This bounds live workers at maxConcurrentWorkers
        // even under racing bursts (the fork-bomb guard).
        val worker = newWorker() ?: return null
        if (live.size > maxConcurrentWorkers) {
            retire(worker)
            return null
        }
        logState("spawned fallback worker pid=${worker.pid()} (pool saturated)")
        if (worker.awaitReady(readyTimeoutMillis) && worker.tryClaim()) return worker
        retire(worker)
        return null
    }

    /** Refills the idle pool up to [poolSize], respecting the ceiling. */
    private fun refillToTarget() {
        if (closed.get()) return
        // Sweep dead parked workers first.
        idle.removeIf { it.isDead.also { dead -> if (dead) retire(it) } }
        while (!closed.get() &&
            !Thread.currentThread().isInterrupted &&
            idle.size < poolSize &&
            live.size < maxConcurrentWorkers
        ) {
            val worker = newWorker() ?: break
            // Ready-await happens off the caller path (we are on the refiller thread).
            if (worker.awaitReady(readyTimeoutMillis) && worker.isIdle) {
                idle.add(worker)
                logState("worker ready pid=${worker.pid()}")
            } else {
                // Diagnostic: a worker that dies before the ready sentinel (e.g. a
                // failed bwrap cage setup on the child side) would otherwise only
                // ever show up as a silent timeout — surface its captured stderr.
                val stderr = worker.stderrSnapshot().trim()
                retire(worker)
                log(
                    "worker failed to become ready; discarded" +
                        if (stderr.isNotEmpty()) " — child stderr: $stderr" else " (no child stderr captured)",
                )
            }
        }
    }

    private fun scheduleRefill() {
        if (closed.get()) return
        runCatching { refiller.execute { refillToTarget() } }
    }

    /** Launches a new worker and registers it as live. Returns null on launch failure. */
    private fun newWorker(): WarmScriptWorker? =
        try {
            startingCount.incrementAndGet()
            val w = WarmScriptWorker(timeoutSeconds, maxHeapMb, javaBinary, classpath)
            live.add(w)
            w
        } catch (e: Exception) {
            log("failed to launch worker: ${e::class.simpleName}: ${e.message}")
            null
        } finally {
            startingCount.decrementAndGet()
        }

    /** Removes a worker from tracking and kills it if still alive. Idempotent. */
    private fun retire(worker: WarmScriptWorker) {
        live.remove(worker)
        idle.remove(worker)
        worker.destroy()
    }

    private fun logState(action: String) {
        log(
            "$action | idle=${idle.size} live=${live.size} starting=${startingCount.get()} " +
                "target=$poolSize ceiling=$maxConcurrentWorkers",
        )
    }

    /** Snapshot of pool occupancy (test/diagnostic use). */
    internal fun stats(): Stats = Stats(idle = idle.size, live = live.size, target = poolSize, ceiling = maxConcurrentWorkers)

    internal data class Stats(
        val idle: Int,
        val live: Int,
        val target: Int,
        val ceiling: Int,
    )

    /** OS pids of all currently-live worker processes (test use). */
    internal fun livePidsForTest(): List<Long> = live.toList().map { it.pid() }

    /**
     * Forcibly kills every currently-live worker process (test use), simulating
     * workers crashing out from under the pool. Does NOT remove them from
     * tracking — the pool must *detect* the deaths on its own.
     */
    internal fun killAllLiveForTest() {
        live.toList().forEach { it.destroy() }
    }

    /**
     * Orderly shutdown: stop refilling and forcibly terminate **every** live
     * worker process, so no child JVM outlives the pool (zombie-process leak).
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        refiller.shutdownNow()
        val victims = live.toList()
        idle.clear()
        victims.forEach { runCatching { it.destroy() } }
        live.clear()
        log("closed; terminated ${victims.size} worker process(es)")
    }

    internal companion object {
        const val DEFAULT_POOL_SIZE: Int = 3
        const val DEFAULT_CHECKOUT_TIMEOUT_MILLIS: Long = 4_000
        const val DEFAULT_READY_TIMEOUT_MILLIS: Long = 30_000
        private const val POLL_INTERVAL_MILLIS: Long = 20

        /** Pool size from `KUML_MCP_SANDBOX_POOL_SIZE`, clamped to [1, 16], default 3. */
        fun defaultPoolSize(): Int {
            val raw = System.getenv(ENV_POOL_SIZE)?.trim()?.toIntOrNull() ?: DEFAULT_POOL_SIZE
            return raw.coerceIn(1, 16)
        }

        /**
         * Hard ceiling on concurrently-live worker JVMs, from
         * `KUML_MCP_SANDBOX_MAX_WORKERS`. Defaults to 2× pool size (headroom for
         * burst fallbacks) with a floor of poolSize and an absolute cap of 32.
         */
        fun defaultMaxConcurrent(poolSize: Int): Int {
            val raw = System.getenv(ENV_MAX_WORKERS)?.trim()?.toIntOrNull() ?: (poolSize * 2)
            return raw.coerceIn(poolSize, 32)
        }

        const val ENV_POOL_SIZE: String = "KUML_MCP_SANDBOX_POOL_SIZE"
        const val ENV_MAX_WORKERS: String = "KUML_MCP_SANDBOX_MAX_WORKERS"
    }
}
