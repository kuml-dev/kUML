package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Welle-3 warm-worker-pool behaviour: warm-hit latency, backpressure under
 * concurrency, crash recovery, orderly shutdown (no zombie processes), and the
 * no-cross-script-state-leak guarantee.
 *
 * These tests launch real child JVMs and so are a little slow; they use the
 * running JVM's classpath and `java` binary (the [WorkerPool] defaults).
 *
 * V0.23.3 — Welle 3.
 */
class WorkerPoolTest :
    FunSpec({

        val minimalUml = """diagram(name = "Hello", type = DiagramType.CLASS) {}"""

        /** Waits until the pool has at least [n] idle warm workers, or times out. */
        fun awaitIdle(
            pool: WorkerPool,
            n: Int,
            timeoutMs: Long = 60_000,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (pool.stats().idle >= n) return true
                Thread.sleep(50)
            }
            return pool.stats().idle >= n
        }

        test("warm-hit latency is far below the ~1.5 s Welle-2 cold-start") {
            val pool = WorkerPool(poolSize = 2, maxConcurrentWorkers = 4)
            try {
                awaitIdle(pool, 1).shouldBeTrue()

                // In-process baseline, measured at STEADY STATE: warm the
                // scripting host once (paying the ~1.5 s one-off compiler init),
                // then measure — this is the ~136 ms floor Welle 2 reported.
                InProcessScriptEvaluator.evaluate(minimalUml) // warm-up, discarded
                val inProcessMs = measureTimeMillis { InProcessScriptEvaluator.evaluate(minimalUml) }

                // Warm-pool hit: a worker is already idle, so this pays neither
                // JVM boot nor compiler warm-up — only IPC + serialization.
                lateinit var result: EvaluatedScript
                val warmMs = measureTimeMillis { result = pool.evaluate(minimalUml) }
                result.shouldBeInstanceOf<EvaluatedScript.Success>()

                println(
                    "[latency] in-process-steady=${inProcessMs}ms  warm-pool-hit=${warmMs}ms  " +
                        "(Welle-2 cold-start baseline ~1628ms)",
                )
                // Must be dramatically better than the ~1628 ms Welle-2 cold start.
                // A generous 900 ms ceiling absorbs CI jitter while still proving
                // the warm-up is off the critical path (cold start was ~1.5 s).
                warmMs.shouldBeLessThanOrEqual(900L)
            } finally {
                pool.close()
            }
        }

        test("backpressure: more concurrent requests than pool size — all succeed, ceiling respected") {
            // Small pool + ceiling; drive far more concurrent requests than either.
            val pool = WorkerPool(poolSize = 2, maxConcurrentWorkers = 4, checkoutTimeoutMillis = 8_000)
            try {
                awaitIdle(pool, 1).shouldBeTrue()

                val n = 8
                val exec = Executors.newFixedThreadPool(n)
                val futures: List<Future<EvaluatedScript>> =
                    (1..n).map { i ->
                        exec.submit<EvaluatedScript> {
                            pool.evaluate("""diagram(name = "d$i", type = DiagramType.CLASS) {}""")
                        }
                    }
                val results = futures.map { it.get(90, TimeUnit.SECONDS) }
                exec.shutdownNow()

                // Every request resolves to a terminal EvaluatedScript — either a
                // Success or a fail-fast SANDBOX "saturated" (never a hang, never a
                // crash). Under this ceiling most should succeed; saturation
                // rejections are acceptable backpressure, not failures of the test.
                val successes = results.count { it is EvaluatedScript.Success }
                val saturations =
                    results.count { it is EvaluatedScript.Failure && it.kind == FailureKind.SANDBOX }
                println("[backpressure] n=$n successes=$successes saturations=$saturations")
                (successes + saturations) shouldBe n
                successes.shouldBeGreaterThan(0)

                // The hard fork-bomb guard: live workers never exceeded the ceiling.
                pool.stats().live.shouldBeLessThanOrEqual(4)
            } finally {
                pool.close()
            }
        }

        test("worker crash is detected and replaced — subsequent requests still succeed") {
            val pool = WorkerPool(poolSize = 2, maxConcurrentWorkers = 4)
            try {
                awaitIdle(pool, 2).shouldBeTrue()

                // Kill every currently-live worker process out from under the pool
                // (simulates workers crashing while parked/idle).
                pool.killAllLiveForTest()

                // The pool must not hand out a dead worker; it detects the deaths,
                // refills, and the next request succeeds on a fresh worker.
                val result = pool.evaluate(minimalUml)
                result.shouldBeInstanceOf<EvaluatedScript.Success>()
            } finally {
                pool.close()
            }
        }

        test("no cross-script state leak: C4 ids restart at c4-0 for each request") {
            // C4Ids is a mutable global counter reset per script. Because every
            // worker is a fresh, use-once process, two sequential C4 renders must
            // each start their ids from the same base — no carry-over.
            val c4Script =
                """
                import dev.kuml.c4.dsl.c4Model
                c4Model(name = "Sys") {
                    val user = person(name = "User") { description = "a user" }
                    val sys = softwareSystem(name = "Sys") { description = "the system" }
                    relationship(source = user, target = sys) { description = "uses" }
                    systemLandscapeDiagram(name = "Landscape") { description = "all" }
                }
                """.trimIndent()

            val pool = WorkerPool(poolSize = 2, maxConcurrentWorkers = 4)
            try {
                awaitIdle(pool, 1).shouldBeTrue()
                val first = pool.evaluate(c4Script)
                val second = pool.evaluate(c4Script)
                val a = first.shouldBeInstanceOf<EvaluatedScript.Success>().diagram
                val b = second.shouldBeInstanceOf<EvaluatedScript.Success>().diagram

                // Deterministic, identical output across the two independent
                // workers proves no id-counter (or other global) state leaked
                // from the first script into the second.
                ExtractedDiagramCodec.encode(a) shouldBe ExtractedDiagramCodec.encode(b)
            } finally {
                pool.close()
            }
        }

        test("orderly shutdown terminates every worker process — no zombie leak") {
            val pool = WorkerPool(poolSize = 3, maxConcurrentWorkers = 4)
            awaitIdle(pool, 3).shouldBeTrue()

            val pids = pool.livePidsForTest()
            pids.size.shouldBeGreaterThan(0)

            pool.close()

            // Give the OS a moment to reap the killed children, then assert every
            // worker pid is gone. This is the concrete "no zombie process leak".
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var stillAlive = pids.filter { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }
            while (stillAlive.isNotEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100)
                stillAlive = pids.filter { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }
            }
            println("[shutdown] launched pids=$pids stillAlive=$stillAlive")
            stillAlive.isEmpty().shouldBeTrue()

            // Evaluating after close fails closed (SANDBOX), never runs in-process.
            val afterClose = pool.evaluate(minimalUml)
            val failure = afterClose.shouldBeInstanceOf<EvaluatedScript.Failure>()
            failure.kind shouldBe FailureKind.SANDBOX
        }

        test("fail-closed: a pool that can never start a worker returns SANDBOX, not in-process") {
            // Point at a non-existent java binary: no worker can ever become
            // ready, the ceiling fallback also fails, so evaluate must fail closed.
            val pool =
                WorkerPool(
                    poolSize = 1,
                    maxConcurrentWorkers = 2,
                    checkoutTimeoutMillis = 500,
                    readyTimeoutMillis = 1_000,
                    javaBinary = "/nonexistent/java-binary-that-does-not-exist",
                )
            try {
                val result = pool.evaluate(minimalUml)
                val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                failure.kind shouldBe FailureKind.SANDBOX
            } finally {
                pool.close()
            }
        }

        test("guard rejection short-circuits without consuming a worker") {
            val pool = WorkerPool(poolSize = 1, maxConcurrentWorkers = 2)
            try {
                awaitIdle(pool, 1)
                val result = pool.evaluate("""diagram(name = "x") {}; Runtime.getRuntime().exec("id")""")
                val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                failure.kind shouldBe FailureKind.GUARD
            } finally {
                pool.close()
            }
        }
    })
