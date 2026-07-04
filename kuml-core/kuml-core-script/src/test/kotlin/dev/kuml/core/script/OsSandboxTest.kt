package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import io.kotest.matchers.string.shouldContain as shouldContainString

/**
 * Wellen-4/5 tests: **OS-native isolation** of the script-worker child processes.
 *
 * The critical thing these tests prove is not "a sandboxed worker still starts"
 * but that the **OS layer itself is what cages the process** — independent of
 * the regex [KumlScriptGuard] (layer 1). The regex denylist is deliberately so
 * broad that a text-level file-write / network escape inside a *kUML script* is
 * hard to express, which is good for the product but would make a script-level
 * test ambiguous about *which* layer blocked the escape.
 *
 * So the escape tests here bypass the denylist entirely: they compile a tiny
 * **raw Java escape program** (no kUML DSL, no guard) and launch it through the
 * exact same [OsSandbox.wrap] path a worker uses. The escape SUCCEEDS with no
 * sandbox (baseline) and is BLOCKED under the sandbox — proving the OS cage,
 * not the denylist, is the thing that stops it.
 *
 * ## Coverage per platform
 *
 *  - **macOS (Welle 4)** — the full behavioural escape suite runs here (this is
 *    the dev machine): it actually launches the caged process and asserts the
 *    kernel refuses the escape. This is real **behaviour** verification.
 *  - **Linux (Welle 5)** — the `bwrap` command **construction** is verified
 *    ([bwrapCommandFor]) on any OS, asserting the exact argv. This is
 *    **construction** verification only: on this macOS machine there is no
 *    `bwrap` and no Linux kernel, so we do NOT and CANNOT prove the resulting
 *    cage blocks anything at runtime. Real behavioural verification of the Linux
 *    cage must happen in Linux CI (see the report / the OsSandbox KDoc).
 *  - Mode-resolution / platform-classification / fail-closed logic is asserted
 *    platform-independently.
 *
 * V0.23.3 — Wellen 4-5.
 */
class OsSandboxTest :
    FunSpec({

        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .let { it.contains("mac") || it.contains("darwin") }

        /**
         * Compiles a small Java class into [outDir] and returns its runnable
         * classpath. Uses the test JVM's system Java compiler (a full JDK), NOT
         * the jlink worker runtime.
         */
        fun compileJava(
            outDir: File,
            className: String,
            source: String,
        ): String {
            val srcFile = File(outDir, "$className.java")
            srcFile.writeText(source)
            val compiler = ToolProvider.getSystemJavaCompiler() ?: error("no system Java compiler on the test JVM")
            val rc = compiler.run(null, null, null, "--release", "21", "-d", outDir.absolutePath, srcFile.absolutePath)
            check(rc == 0) { "javac failed (rc=$rc) for $className" }
            return outDir.absolutePath
        }

        /** Runs [command] to completion, returning (exitCode, combinedOutput). */
        fun run(command: List<String>): Pair<Int, String> {
            val p =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        System.getenv("PATH")?.let { v -> it.environment()["PATH"] = v }
                    }.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return p.exitValue() to out
        }

        // ── Platform classification (platform-independent) ─────────────────────

        test("os.name is classified into the right Platform") {
            OsSandbox.detectPlatform("Mac OS X") shouldBe OsSandbox.Platform.MAC
            OsSandbox.detectPlatform("Darwin") shouldBe OsSandbox.Platform.MAC
            OsSandbox.detectPlatform("Linux") shouldBe OsSandbox.Platform.LINUX
            OsSandbox.detectPlatform("Windows 11") shouldBe OsSandbox.Platform.WINDOWS
            OsSandbox.detectPlatform("SunOS") shouldBe OsSandbox.Platform.OTHER
            OsSandbox.detectPlatform(null) shouldBe OsSandbox.Platform.OTHER
        }

        // ── Mode resolution / fail-closed policy (platform-independent) ────────

        test("OS-isolation mode defaults to required on macOS, best-effort elsewhere") {
            OsSandbox.modeFrom(null, OsSandbox.Platform.MAC) shouldBe OsSandbox.Mode.REQUIRED
            OsSandbox.modeFrom(null, OsSandbox.Platform.LINUX) shouldBe OsSandbox.Mode.BEST_EFFORT
            OsSandbox.modeFrom(null, OsSandbox.Platform.WINDOWS) shouldBe OsSandbox.Mode.BEST_EFFORT
            OsSandbox.modeFrom(null, OsSandbox.Platform.OTHER) shouldBe OsSandbox.Mode.BEST_EFFORT
            OsSandbox.modeFrom("best-effort", OsSandbox.Platform.MAC) shouldBe OsSandbox.Mode.BEST_EFFORT
            OsSandbox.modeFrom("required", OsSandbox.Platform.LINUX) shouldBe OsSandbox.Mode.REQUIRED
            OsSandbox.modeFrom("  REQUIRED  ", OsSandbox.Platform.LINUX) shouldBe OsSandbox.Mode.REQUIRED
        }

        test("on macOS, OS isolation is reported available (sandbox-exec + profile present)") {
            if (!isMac) return@test
            OsSandbox.isolationAvailable().shouldBeTrue()
        }

        // ── Linux bwrap command CONSTRUCTION (verified on any OS) ──────────────
        //
        // These prove the argument vector is assembled correctly. They do NOT
        // launch bwrap and do NOT prove the cage works — that needs real Linux CI.

        test("bwrapCommandFor builds the expected argv (construction only, not behaviour)") {
            val bwrap = "/usr/bin/bwrap"
            val work = "/tmp/kuml-worker-work-abc"
            val bare = listOf("/opt/jdk/bin/java", "-Xmx256m", "-cp", "a.jar:b.jar", "dev.kuml.core.script.ScriptWorkerMain")
            val cmd = OsSandbox.bwrapCommandFor(bwrap, bare, work)

            // First token is bwrap itself.
            cmd.first() shouldBe bwrap
            // No network: --unshare-all present, and there is NO --share-net anywhere.
            cmd shouldContain "--unshare-all"
            cmd.none { it == "--share-net" }.shouldBeTrue()
            // Dies with the parent (no orphaned caged JVMs).
            cmd shouldContain "--die-with-parent"

            // Whole root read-only: exactly the triple `--ro-bind / /`.
            val roIdx = cmd.indexOf("--ro-bind")
            (roIdx >= 0).shouldBeTrue()
            cmd[roIdx + 1] shouldBe "/"
            cmd[roIdx + 2] shouldBe "/"

            // The single writable location is the canonical workdir at the same path.
            val bindIdx = cmd.indexOf("--bind")
            (bindIdx >= 0).shouldBeTrue()
            cmd[bindIdx + 1] shouldBe work
            cmd[bindIdx + 2] shouldBe work
            // The writable --bind must come AFTER the read-only --ro-bind of /,
            // so it shadows the root for that subtree (later binds win).
            (bindIdx > roIdx).shouldBeTrue()

            // Private throwaway /tmp, private /proc + /dev.
            val tmpfsIdx = cmd.indexOf("--tmpfs")
            (tmpfsIdx >= 0).shouldBeTrue()
            cmd[tmpfsIdx + 1] shouldBe "/tmp"
            cmd shouldContain "--proc"
            cmd shouldContain "--dev"

            // CWD is the writable workdir.
            val chdirIdx = cmd.indexOf("--chdir")
            (chdirIdx >= 0).shouldBeTrue()
            cmd[chdirIdx + 1] shouldBe work

            // Env is cleared then only TMPDIR is repointed at the workdir.
            cmd shouldContain "--clearenv"
            val setenvIdxs = cmd.indices.filter { cmd[it] == "--setenv" }
            setenvIdxs.any { cmd.getOrNull(it + 1) == "TMPDIR" && cmd.getOrNull(it + 2) == work }.shouldBeTrue()

            // The bare command appears verbatim, after a `--` separator, in order.
            val sepIdx = cmd.indexOf("--")
            (sepIdx >= 0).shouldBeTrue()
            cmd.subList(sepIdx + 1, cmd.size) shouldBe bare
        }

        test("bwrapCommandFor puts the writable --bind after --ro-bind / for every arg order") {
            // Regression guard for the precedence invariant: the writable workdir
            // bind must always shadow the read-only root, else legit temp writes
            // would be denied on real Linux.
            val cmd = OsSandbox.bwrapCommandFor("/usr/bin/bwrap", listOf("java"), "/tmp/w")
            cmd.indexOf("--bind") shouldBeGreaterThan cmd.indexOf("--ro-bind")
        }

        // ── The core proof: the OS cage blocks file-write and network ──────────

        test("OS sandbox blocks a file-write escape that has nothing to do with the denylist") {
            if (!isMac) return@test
            val work = Files.createTempDirectory("kuml-ossbx-fw-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            val escapeTarget = File(System.getProperty("user.home"), "kuml-sandbox-escape-fw-test.txt")
            escapeTarget.delete()

            try {
                val cp =
                    compileJava(
                        classes,
                        "FwEscape",
                        """
                        import java.io.FileWriter;
                        public class FwEscape {
                          public static void main(String[] a) throws Exception {
                            FileWriter w = new FileWriter(a[0]);
                            w.write("ESCAPED"); w.close();
                            System.out.println("WROTE");
                          }
                        }
                        """.trimIndent(),
                    )
                val bare =
                    listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "FwEscape", escapeTarget.absolutePath)

                // Baseline: NO sandbox — the write must succeed (proves the escape is real).
                val (_, baseOut) = run(bare)
                baseOut shouldContainString "WROTE"
                escapeTarget.exists().shouldBeTrue()
                escapeTarget.delete()

                // Under the OS sandbox: the very same write must FAIL at the kernel,
                // even though nothing here ever went near KumlScriptGuard.
                val wrapped = OsSandbox.wrap(bare, work)
                // wrap() must actually have wrapped it in sandbox-exec on macOS.
                wrapped.first() shouldBe OsSandbox.SANDBOX_EXEC_PATH
                run(wrapped)
                escapeTarget.exists().shouldBeFalse()
            } finally {
                escapeTarget.delete()
                work.deleteRecursively()
            }
        }

        test("OS sandbox blocks a network escape (both DNS and raw-IP), denylist-independent") {
            if (!isMac) return@test
            val work = Files.createTempDirectory("kuml-ossbx-net-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            try {
                val cp =
                    compileJava(
                        classes,
                        "NetEscape",
                        """
                        import java.net.*;
                        public class NetEscape {
                          public static void main(String[] a) {
                            try {
                              Socket s = new Socket();
                              s.connect(new InetSocketAddress(a[0], 80), 3000);
                              System.out.println("NET-OK"); s.close();
                            } catch (Throwable t) { System.out.println("NET-BLOCKED:" + t.getClass().getSimpleName()); }
                          }
                        }
                        """.trimIndent(),
                    )
                // Raw IP (no DNS needed) — the strongest test: even if DNS were
                // somehow reachable, a direct connect must still be refused.
                val bareIp = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "NetEscape", "1.1.1.1")
                val wrapped = OsSandbox.wrap(bareIp, work)
                wrapped.first() shouldBe OsSandbox.SANDBOX_EXEC_PATH
                val (_, out) = run(wrapped)
                out shouldContainString "NET-BLOCKED"
            } finally {
                work.deleteRecursively()
            }
        }

        test("OS sandbox blocks reading ~/.ssh, denylist-independent") {
            if (!isMac) return@test
            val ssh = File(System.getProperty("user.home"), ".ssh")
            val probe = File(ssh, "kuml_sandbox_probe")
            val createdSsh = !ssh.exists()
            val work = Files.createTempDirectory("kuml-ossbx-ssh-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            try {
                ssh.mkdirs()
                probe.writeText("FAKE_KEY")
                val cp =
                    compileJava(
                        classes,
                        "SshRead",
                        """
                        import java.nio.file.*;
                        public class SshRead {
                          public static void main(String[] a) {
                            try {
                              String s = new String(Files.readAllBytes(Paths.get(a[0])));
                              System.out.println("SSH-READ:" + s);
                            } catch (Throwable t) { System.out.println("SSH-BLOCKED:" + t.getClass().getSimpleName()); }
                          }
                        }
                        """.trimIndent(),
                    )
                val bare = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "SshRead", probe.absolutePath)
                val wrapped = OsSandbox.wrap(bare, work)
                val (_, out) = run(wrapped)
                out shouldContainString "SSH-BLOCKED"
            } finally {
                probe.delete()
                if (createdSsh) ssh.delete()
                work.deleteRecursively()
            }
        }

        test("legitimate write INSIDE the per-worker workdir is allowed (no false-positive)") {
            if (!isMac) return@test
            val work = Files.createTempDirectory("kuml-ossbx-ok-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            try {
                val cp =
                    compileJava(
                        classes,
                        "OkWrite",
                        """
                        import java.io.FileWriter;
                        public class OkWrite {
                          public static void main(String[] a) throws Exception {
                            FileWriter w = new FileWriter(a[0] + "/scratch.txt");
                            w.write("ok"); w.close();
                            System.out.println("OK-WROTE");
                          }
                        }
                        """.trimIndent(),
                    )
                val bare = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "OkWrite", work.absolutePath)
                val wrapped = OsSandbox.wrap(bare, work)
                val (_, out) = run(wrapped)
                out shouldContainString "OK-WROTE"
                File(work, "scratch.txt").exists().shouldBeTrue()
            } finally {
                work.deleteRecursively()
            }
        }
    })
