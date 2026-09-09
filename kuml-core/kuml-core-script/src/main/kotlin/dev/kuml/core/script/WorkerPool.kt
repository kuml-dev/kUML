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
            return EvaluatedScript.Failure(kind = FailureKind.SANDBOX, message = "Script sandbox pool is shut down.")
        }

        // Layer 1 guard in the parent, before spending a worker on a hostile
        // script (defence in depth; the worker re-runs it too).
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(kind = FailureKind.GUARD, message = e.message ?: "kUML script rejected by security guard.")
        }

        val worker =
            checkoutIdleWorker()
                ?: waitForWorker()
                ?: spawnFallbackWorker()
                ?: return EvaluatedScript.Failure(
                    kind = FailureKind.SANDBOX,
                    message =
                        "Script sandbox pool saturated: no worker available and the concurrent-worker ceiling " +
                            "($maxConcurrentWorkers) is reached. Retry shortly.",
                )

        logState("assigned worker pid=${worker.pid()}")
        return try {
            worker.evaluate(source = source, fileName = fileName)
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
                // The pool can have been closed while we waited for the ready
                // line. That worker sits behind close()'s drain and would never
                // be terminated — reap it instead of leaking a child JVM.
                if (!enqueueReadyOrReap(worker = worker, idle = idle, live = live, closed = closed::get) { it.destroy() }) {
                    return
                }
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
            val w = WarmScriptWorker(timeoutSeconds = timeoutSeconds, maxHeapMb = maxHeapMb, javaBinary = javaBinary, classpath = classpath)
            // close() can have drained `live` while this worker was starting. A
            // worker registered after the drain would never be terminated by
            // anyone — reap it here instead of leaking a child JVM.
            //
            // `closed` is passed as a supplier (`closed::get`), NOT a pre-computed
            // Boolean: Kotlin evaluates call-site arguments before entering the
            // function body, so a `closed = closed.get()` argument would read the
            // flag BEFORE `registerOrReap`'s body runs `live.add(worker)` — exactly
            // the publish-then-check order that makes the reap-guard race-proof
            // (see `registerOrReap`'s KDoc). Passing a supplier lets the body read
            // the flag AFTER publishing to `live`, restoring that guarantee.
            registerOrReap(worker = w, live = live, closed = closed::get) { it.destroy() }
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
    internal fun livePidsForTest(): List<Long> = live.concurrentSnapshot().map { it.pid() }

    /**
     * Forcibly kills every currently-live worker process (test use), simulating
     * workers crashing out from under the pool. Does NOT remove them from
     * tracking — the pool must *detect* the deaths on its own.
     */
    internal fun killAllLiveForTest() {
        live.concurrentSnapshot().forEach { runCatching { it.destroy() } }
    }

    /**
     * Orderly shutdown: stop refilling and forcibly terminate **every** live
     * worker process, so no child JVM outlives the pool (zombie-process leak).
     *
     * Idempotent **and effective on every call**: the one-time parts (stopping
     * the refiller, the closing log line) run only on the first call, but the
     * drain itself runs on every call. An earlier call that broke off halfway,
     * or lost a race against the refiller, must not leave a later call doing
     * nothing just because the `closed` flag is already set — on an empty set
     * the drain costs one `firstOrNull()`.
     *
     * Bewusst **nicht** `@Synchronized`: `close()` wird sowohl explizit als auch
     * aus dem JVM-Shutdown-Hook aufgerufen ([ScriptEvaluators.registerShutdownHook]);
     * ein Lock, der über den gesamten Drain hinweg blockiert, wäre schlimmer als
     * zwei parallel laufende Drains (die sich über die atomare `remove`-Operation
     * sauber aufteilen). Das schließt ein **beschränktes** Warten im Hook nicht
     * aus: der erste `close()`-Aufruf wartet vor dem Drain bis zu
     * [REFILLER_SHUTDOWN_MILLIS] (2 s) auf die Refiller-Terminierung — dieses
     * kurze, obergrenzenbeschränkte Warten wird im Hook bewusst in Kauf
     * genommen, weil ohne es der Refiller einen gerade gestarteten Worker nach
     * dem Drain in `live` eintragen könnte (siehe [REFILLER_SHUTDOWN_MILLIS]).
     */
    override fun close() {
        val firstClose = closed.compareAndSet(false, true)
        if (firstClose) {
            // Refiller VOR dem Drain stoppen und auf sein Ende warten: shutdownNow()
            // unterbricht nur, wartet aber nicht — ohne dieses Warten kann der
            // Refiller einen gerade gestarteten Worker nach dem Drain in `live`
            // eintragen, wo ihn niemand mehr beendet.
            runCatching { refiller.shutdownNow() }
            try {
                refiller.awaitTermination(REFILLER_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // The calling thread was itself interrupted while waiting (e.g. a
                // shutdown path that unblocks close() by interrupting its caller).
                // Restore the flag instead of swallowing it — see
                // WarmScriptWorker.awaitReady for the same convention.
                Thread.currentThread().interrupt()
            }
        }
        idle.clear()
        val terminated =
            drainAndDestroy(
                workers = live,
                maxIterations = maxConcurrentWorkers + DRAIN_ITERATION_SLACK,
            ) { worker -> worker.destroy() }
        // drainAndDestroy silently gives up once it hits its livelock guard
        // (maxIterations) — if that happened while `live` was still non-empty,
        // those worker JVMs were neither terminated nor logged, i.e. exactly the
        // zombie-process leak this method exists to prevent, just now invisible
        // instead of loud. Surface it so an operator can investigate, even though
        // `close()` itself still returns normally (best-effort shutdown).
        val stillLive = live.size
        if (stillLive > 0) {
            log(
                "close() drain hit its iteration cap ($DRAIN_ITERATION_SLACK slack) with " +
                    "$stillLive worker(s) still tracked in `live` — they were NOT terminated; " +
                    "possible zombie child JVM(s), investigate",
            )
        }
        if (firstClose) log("closed; terminated $terminated worker process(es)")
    }

    internal companion object {
        const val DEFAULT_POOL_SIZE: Int = 3
        const val DEFAULT_CHECKOUT_TIMEOUT_MILLIS: Long = 4_000
        const val DEFAULT_READY_TIMEOUT_MILLIS: Long = 30_000
        private const val POLL_INTERVAL_MILLIS: Long = 20

        /**
         * Wartebudget für den Refiller-Thread beim Shutdown. `shutdownNow()`
         * unterbricht nur; ohne dieses Warten kann der Refiller einen gerade
         * gestarteten Worker NACH dem Drain in `live` eintragen (→ ungetrackte,
         * nie beendete Kind-JVM).
         */
        private const val REFILLER_SHUTDOWN_MILLIS: Long = 2_000

        /**
         * Zusätzliche Iterationen über [maxConcurrentWorkers] hinaus, die der
         * Shutdown-Drain toleriert, bevor er abbricht. Reine Livelock-Schranke
         * für den pathologischen Fall, dass ein Thread während des Drains noch
         * Worker nachlegt — normal wird sie nie erreicht.
         */
        private const val DRAIN_ITERATION_SLACK: Int = 64

        /**
         * Leert [workers] Element für Element und beendet jedes über [destroy].
         *
         * **Warum kein `toList()`-Snapshot:** Kotlins `Iterable<T>.toList()` hat einen
         * `size == 1`-Schnellpfad (`listOf(iterator().next())`, kotlin-stdlib 2.4.0
         * `_Collections.kt:1499`), der `size` und `iterator().next()` als zwei
         * unabhängige Operationen ausführt. Auf einer schwach konsistenten
         * `ConcurrentHashMap.KeySetView` lässt eine nebenläufige Entfernung zwischen
         * beiden Schritten `next()` mit `NoSuchElementException` fehlschlagen. Weil
         * der Shutdown das `closed`-Flag bereits gesetzt hat, würde ein zweiter
         * `close()` sofort zurückkehren und die destroy-Sequenz liefe **nie** — die
         * Kind-JVMs überlebten den Pool (genau der Zombie-Leak, den diese Klasse
         * ausschließt). `firstOrNull()` dagegen benutzt `hasNext()`/`next()` auf
         * demselben, vorausschauenden CHM-Iterator und ist deshalb race-frei.
         *
         * Beendet ein Element, das ein nebenläufiger `retire()` bereits entfernt hat,
         * gegebenenfalls ein zweites Mal — [WarmScriptWorker.destroy] ist idempotent.
         * Gezählt werden nur Entfernungen, die dieser Drain selbst gewonnen hat.
         *
         * @param maxIterations harte Livelock-Schranke.
         * @return Anzahl der von diesem Aufruf beendeten Worker.
         */
        internal fun <T : Any> drainAndDestroy(
            workers: MutableSet<T>,
            maxIterations: Int,
            destroy: (T) -> Unit,
        ): Int {
            var terminated = 0
            var iterations = 0
            while (iterations < maxIterations) {
                iterations++
                val victim = workers.firstOrNull() ?: break
                val wonRemoval = workers.remove(victim)
                runCatching { destroy(victim) }
                if (wonRemoval) terminated++
            }
            return terminated
        }

        /**
         * Registers [worker] into [live] unless the pool has already been closed,
         * in which case it is removed again and destroyed instead of being left
         * as an untracked child JVM that no one will ever terminate.
         *
         * **Publish-then-check order is load-bearing.** [worker] is added to
         * [live] *first*, and only then is [closed] consulted — as a *supplier*
         * invoked from inside this function body, not a pre-computed `Boolean`.
         * This makes the race impossible: `close()` sets its flag via CAS
         * ([WorkerPool.close]) strictly *before* it drains [live]. So either
         * the drain runs after this function's `live.add`, in which case it
         * will see and destroy [worker] — or [closed] is read as `true` here
         * (meaning the CAS, and therefore eventually the drain, already
         * happened-before this call), in which case this function itself
         * removes and destroys [worker]. There is no interleaving that leaves
         * [worker] in [live] with neither the drain nor this function ever
         * having destroyed it.
         *
         * A caller that instead evaluates the closed flag at the *call site*
         * (e.g. `closed = closed.get()`) breaks this guarantee: Kotlin
         * evaluates arguments before entering the function body, so the flag
         * would be read *before* `live.add` runs here — reopening exactly the
         * leak this function exists to close. Always pass a supplier
         * (`closed::get`), never a pre-computed value.
         *
         * Extracted as the deterministic, dependency-free seam behind
         * [newWorker]'s close-race reap-guard so it can be unit-tested without
         * spawning a real worker process — see `registerOrReap` tests in
         * `WorkerPoolTest`.
         *
         * @return [worker] if it is now tracked in [live], or `null` if it was
         *   reaped because the pool is closed.
         */
        internal fun <T : Any> registerOrReap(
            worker: T,
            live: MutableSet<T>,
            closed: () -> Boolean,
            destroy: (T) -> Unit,
        ): T? {
            live.add(worker)
            if (!closed()) return worker
            live.remove(worker)
            destroy(worker)
            return null
        }

        /**
         * Enqueues [worker] into [idle] unless the pool has already been closed,
         * in which case it is removed from [live] and destroyed instead — the
         * mirror of [registerOrReap] for the ready-callback path in
         * [refillToTarget], which fires later (after `awaitReady()` returns) and
         * so races a concurrent `close()` independently.
         *
         * [closed] is a *supplier*, invoked from inside this function body, for
         * the same reason as [registerOrReap]'s `closed` parameter: it keeps the
         * call site from being able to read the flag before this function runs.
         * By the time this is called, [worker] is already published in [live]
         * (via [registerOrReap] in [newWorker]), so unlike [registerOrReap] the
         * ordering here is not itself load-bearing for correctness — a
         * concurrent `close()` drain would still find and destroy [worker] via
         * [live] regardless of exactly when this reads [closed]. The supplier
         * form is kept anyway so both reap-guards follow one discipline and a
         * future refactor can't quietly reintroduce a pre-computed-argument bug
         * by copying the "wrong" sibling.
         *
         * Extracted for the same reason as [registerOrReap]: a deterministic seam
         * that unit tests can drive with plain values instead of a real worker
         * process — see `enqueueReadyOrReap` tests in `WorkerPoolTest`.
         *
         * @return `true` if [worker] is now enqueued in [idle], `false` if it was
         *   reaped because the pool is closed.
         */
        internal fun <T : Any> enqueueReadyOrReap(
            worker: T,
            idle: MutableCollection<T>,
            live: MutableSet<T>,
            closed: () -> Boolean,
            destroy: (T) -> Unit,
        ): Boolean {
            if (closed()) {
                live.remove(worker)
                destroy(worker)
                return false
            }
            idle.add(worker)
            return true
        }

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

/**
 * Snapshot einer schwach konsistenten nebenläufigen Menge.
 *
 * Bewusst **nicht** `toList()` — siehe [WorkerPool.drainAndDestroy] für den
 * `size == 1`-Schnellpfad, der auf einer `ConcurrentHashMap.KeySetView` mit
 * `NoSuchElementException` fehlschlagen kann. `hasNext()`/`next()` auf
 * demselben Iterator ist dagegen sicher, und die `ArrayList` verkraftet
 * nebenläufiges Wachstum wie Schrumpfen.
 */
private fun <T> Set<T>.concurrentSnapshot(): List<T> {
    val out = ArrayList<T>(size + 4)
    val iterator = iterator()
    while (iterator.hasNext()) out.add(iterator.next())
    return out
}
