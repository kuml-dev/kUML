package dev.kuml.core.script.style

import dev.kuml.core.script.KumlScriptGuard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [NamedArgumentStyleCheck]'s upfront DoS guards (size cap +
 * concurrency ceiling), added to close the gap where an oversized or highly
 * concurrent `script`/`source` payload could reach the Kotlin-compiler
 * child-JVM worker unconditionally (see the class KDoc's "DoS guards"
 * section).
 *
 * Both guards short-circuit in [NamedArgumentStyleCheck.check] *before* the
 * worker library is even resolved, so these tests hold regardless of whether
 * a real `:kuml-style-worker` installation is present on this machine — no
 * fixture/child-process infrastructure required.
 */
class NamedArgumentStyleCheckDosGuardTest :
    FunSpec({

        test("a source exceeding KumlScriptGuard.MAX_SCRIPT_LENGTH is rejected before touching the worker") {
            val oversized = "x".repeat(KumlScriptGuard.MAX_SCRIPT_LENGTH + 1)

            val result = NamedArgumentStyleCheck.check(source = oversized, fileName = "oversized.kuml.kts")

            result.shouldBeInstanceOf<StyleCheckResult.Unavailable>()
            result.reason shouldContain "exceeds the maximum length"
        }

        test("a source at exactly MAX_SCRIPT_LENGTH is not rejected by the size guard") {
            // Right at the boundary — must fall through past the size check
            // (which itself degrades to Unavailable for an unrelated reason in
            // a test environment without the worker lib installed), not the
            // size-cap message specifically.
            val atLimit = "x".repeat(KumlScriptGuard.MAX_SCRIPT_LENGTH)

            val result = NamedArgumentStyleCheck.check(source = atLimit, fileName = "at-limit.kuml.kts")

            result.shouldBeInstanceOf<StyleCheckResult.Unavailable>()
            result.reason shouldNotContain "exceeds the maximum length"
        }

        test("a well-formed small source is not rejected by the size guard") {
            val result = NamedArgumentStyleCheck.check(source = """diagram(name = "Hello") {}""", fileName = "hello.kuml.kts")

            // Environment-dependent below this point (worker lib may or may not
            // be installed on the machine running the test) — only assert the
            // size guard specifically did not fire.
            if (result is StyleCheckResult.Unavailable) {
                result.reason shouldNotContain "exceeds the maximum length"
            }
        }

        test("many concurrent calls beyond the concurrency ceiling never hang and always return a result") {
            // Drives more callers than MAX_CONCURRENT_WORKERS (default 4) at
            // once. Each call either runs the real worker (if installed) or
            // fails fast at libDir resolution / the acquire-timeout path — the
            // guarantee under test is that check() never throws and never
            // blocks past its own bounded timeouts, regardless of which path
            // each caller takes.
            val callers = 12
            val executor = Executors.newFixedThreadPool(callers)
            val startLatch = CountDownLatch(1)
            val results = java.util.Collections.synchronizedList(mutableListOf<StyleCheckResult>())

            try {
                val futures =
                    (1..callers).map { i ->
                        executor.submit {
                            startLatch.await()
                            results.add(
                                NamedArgumentStyleCheck.check(source = "diagram(name = \"C$i\") {}", fileName = "c$i.kuml.kts"),
                            )
                        }
                    }
                startLatch.countDown()
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            results.size shouldBe callers
            // Every call must produce *some* result (never throw, never hang) —
            // the concurrency guard degrades to Unavailable, it never
            // propagates an exception to the caller.
            results.all { it is StyleCheckResult.Ok || it is StyleCheckResult.Unavailable } shouldBe true
        }
    })
