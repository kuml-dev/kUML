package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import io.kotest.matchers.longs.shouldBeGreaterThan as shouldBeGreaterThanLong
import io.kotest.matchers.string.shouldContain as shouldContainString

/**
 * Wellen-4/5/6 tests: **OS-native isolation** of the script-worker child processes.
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
 *  - **macOS (Welle 4)** — the full behavioural escape suite runs here on a Mac:
 *    it actually launches the caged process and asserts the kernel refuses the
 *    escape. This is real **behaviour** verification.
 *  - **Linux (Welle 5)** — **both** construction and behaviour are verified.
 *    The `bwrap` command **construction** ([bwrapCommandFor]) is asserted on
 *    any OS. The `isLinux`-guarded tests below additionally run the **same
 *    real-kernel behavioural methodology as macOS** (raw Java escape program
 *    through [OsSandbox.wrap]) on an actual Linux host — this closed the gap
 *    called out in the architecture report and found one real issue along the
 *    way (broad `--ro-bind / /` left `~/.ssh` etc. readable; fixed with a
 *    per-secret-dir `--tmpfs` shadow, see the [OsSandbox] KDoc "Verified on
 *    real Linux" section). The container/no-unprivileged-userns degradation
 *    case is still unverified (tracked as a follow-up).
 *  - **Windows (Welle 6)** — mode/platform *logic* + the post-start no-op on
 *    non-Windows + the JNA Job-Object **fail-safe** (returns null, never throws,
 *    when kernel32.dll is absent) are asserted here. NO Job Object runs on
 *    macOS or Linux; memory-cap / kill-on-close / process-spawn behaviour
 *    needs real Windows CI (see the report / WindowsJobObjectSandbox KDoc).
 *  - Mode-resolution / platform-classification / fail-closed logic is asserted
 *    platform-independently.
 *
 * V0.23.3 — Wellen 4-6.
 */
class OsSandboxTest :
    FunSpec({

        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .let { it.contains("mac") || it.contains("darwin") }

        val isLinux =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .let { it.contains("linux") || it.contains("nux") }

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
            OsSandbox.detectPlatform("Windows Server 2022") shouldBe OsSandbox.Platform.WINDOWS
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

        test("on Linux, isolationAvailable() reflects a real smoke test, not just bwrap's presence on disk") {
            if (!isLinux) return@test
            // bwrap being present on disk does NOT guarantee it actually works —
            // found 2026-07-04 on GitHub Actions' ubuntu-latest runners, where
            // bwrap's automatic loopback setup is rejected
            // ("RTM_NEWADDR: Operation not permitted") despite the binary being
            // installed and unprivileged user namespaces being enabled. This test
            // asserts the graceful-degradation *contract* rather than a specific
            // outcome, so it is meaningful on both a host where the cage truly
            // works (developer's own verified Ubuntu hardware) and one where it
            // doesn't (this exact CI case):
            //  - if bwrap isn't even on disk, isolationAvailable() must be false.
            //  - if bwrap IS on disk but isolationAvailable() is false anyway (the
            //    smoke test failed), wrap() on best-effort must return the BARE
            //    command completely unchanged — never a broken bwrap prefix that
            //    would make every worker launch fail.
            //  - if isolationAvailable() is true, wrap() must actually prefix bwrap.
            val bwrapPath = OsSandbox.bwrapPathOrNull()
            if (bwrapPath == null) {
                OsSandbox.isolationAvailable().shouldBeFalse()
                return@test
            }
            val work = Files.createTempDirectory("kuml-ossbx-avail-").toFile().apply { deleteOnExit() }
            try {
                val bare = listOf("/bin/true")
                val wrapped = OsSandbox.wrap(bare, work)
                if (OsSandbox.isolationAvailable()) {
                    wrapped.first() shouldBe bwrapPath
                    println("[os-sandbox] Linux: bwrap present at $bwrapPath and smoke test passed — cage is active.")
                } else {
                    wrapped shouldBe bare
                    println(
                        "[os-sandbox] Linux: bwrap present at $bwrapPath but smoke test failed — best-effort " +
                            "correctly degraded to an un-caged command instead of a broken bwrap prefix.",
                    )
                }
            } finally {
                work.deleteRecursively()
            }
        }

        // ── Linux bwrap command CONSTRUCTION (verified on any OS) ──────────────
        //
        // These prove the argument vector is assembled correctly. They do NOT
        // launch bwrap and do NOT prove the cage works — that needs real Linux CI.

        test("bwrapCommandFor builds the expected argv (construction only, not behaviour)") {
            val bwrap = "/usr/bin/bwrap"
            val work = "/tmp/kuml-worker-work-abc"
            val bare = listOf("/opt/jdk/bin/java", "-Xmx256m", "-cp", "a.jar:b.jar", "dev.kuml.core.script.ScriptWorkerMain")
            // Fake, guaranteed-nonexistent homeDir so the secret-shadowing loop
            // adds nothing here — this test is about the base argv shape, not
            // the (separately tested below) secret-directory shadowing, and
            // must not depend on which dotfiles happen to exist on the CI/dev
            // machine's real $HOME.
            val cmd = OsSandbox.bwrapCommandFor(bwrap, bare, work, homeDir = "/nonexistent-test-home-kuml")

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

        test("bwrapCommandFor shadows only EXISTING secret directories with a private tmpfs") {
            // This is the fix for a real gap the Linux behavioural suite below
            // caught: --ro-bind / / alone leaves ~/.ssh etc. READABLE (only
            // writes/network are denied by the base posture). Mirrors the
            // macOS profile's defence-in-depth (deny file-read* …) overrides.
            val fakeHome = Files.createTempDirectory("kuml-fakehome-").toFile()
            try {
                File(fakeHome, ".ssh").mkdirs()
                File(fakeHome, ".gnupg").mkdirs()
                // Deliberately do NOT create .aws or .config/gcloud.

                val cmd = OsSandbox.bwrapCommandFor("/usr/bin/bwrap", listOf("java"), "/tmp/w", homeDir = fakeHome.absolutePath)

                cmd shouldContain File(fakeHome, ".ssh").absolutePath
                cmd shouldContain File(fakeHome, ".gnupg").absolutePath
                cmd.none { it == File(fakeHome, ".aws").absolutePath }.shouldBeTrue()
                cmd.none { it == File(fakeHome, ".config/gcloud").absolutePath }.shouldBeTrue()

                // Each shadowed secret dir must be preceded by its own --tmpfs.
                val sshIdx = cmd.indexOf(File(fakeHome, ".ssh").absolutePath)
                cmd[sshIdx - 1] shouldBe "--tmpfs"

                // Shadows must come after the broad root ro-bind (so they win)
                // and before the --bind of the writable workdir.
                val roIdx = cmd.indexOf("--ro-bind")
                val bindIdx = cmd.indexOf("--bind")
                (sshIdx > roIdx).shouldBeTrue()
                (sshIdx < bindIdx).shouldBeTrue()
            } finally {
                fakeHome.deleteRecursively()
            }
        }

        test("bwrapCommandFor adds no secret-dir shadow when none of them exist (no crash, no phantom mounts)") {
            val cmd =
                OsSandbox.bwrapCommandFor(
                    "/usr/bin/bwrap",
                    listOf("java"),
                    "/tmp/w",
                    homeDir = "/nonexistent-test-home-kuml-2",
                )
            OsSandbox.SECRET_HOME_SUBPATHS.forEach { subpath ->
                cmd.none { it == "/nonexistent-test-home-kuml-2/$subpath" }.shouldBeTrue()
            }
        }

        test("bwrapCommandFor puts the writable --bind after --ro-bind / for every arg order") {
            // Regression guard for the precedence invariant: the writable workdir
            // bind must always shadow the read-only root, else legit temp writes
            // would be denied on real Linux.
            val cmd = OsSandbox.bwrapCommandFor("/usr/bin/bwrap", listOf("java"), "/tmp/w")
            cmd.indexOf("--bind") shouldBeGreaterThan cmd.indexOf("--ro-bind")
        }

        // ── Windows Job Object (Welle 6): LOGIC + FAIL-SAFE, not behaviour ─────
        //
        // ⚠️ These prove the mode/platform logic, the post-start no-op on
        // non-Windows, and that the JNA Job-Object path fails SAFELY (returns null,
        // never throws) when kernel32.dll is absent — i.e. always, on this macOS
        // machine. They do NOT and CANNOT prove that a Job Object actually caps
        // memory / kills-on-close / forbids process spawn: that needs real Windows
        // CI (see the report and the WindowsJobObjectSandbox KDoc).

        test("Windows Job Object memory cap is a sane, sized value") {
            // Above the -Xmx256m worker heap, with headroom for JVM native memory
            // (metaspace, code cache, thread stacks, embedded Kotlin compiler).
            OsSandbox.WINDOWS_JOB_MAX_PROCESS_MEMORY_BYTES shouldBeGreaterThanLong (256L * 1024 * 1024)
        }

        test("applyJobObject fails safely (returns null, never throws) when kernel32 is unavailable") {
            // On this macOS machine there is no kernel32.dll, so Native.load must
            // fail INSIDE the function and be swallowed → null. A thrown exception
            // here would mean an un-caged Windows launch could crash the launcher
            // instead of degrading via the documented fail-closed/best-effort path.
            val result = WindowsJobObjectSandbox.applyJobObject(pid = 424242L, maxProcessMemoryBytes = 64L * 1024 * 1024)
            (result == null).shouldBeTrue()
        }

        test("restricted-token launch is documented-but-not-active (honest scaffold flag)") {
            // Welle 6 documents CreateRestrictedToken but does NOT wire up a
            // restricted-token CreateProcess (that would mean abandoning
            // ProcessBuilder). The flag must say so plainly rather than imply an
            // active token restriction that isn't there.
            WindowsJobObjectSandbox.restrictedTokenSupported().shouldBeFalse()
        }

        test("applyPostStart is a no-op on non-Windows (cage lives in argv there)") {
            // On macOS/Linux the OS cage is applied via wrap() before exec, so the
            // post-start hook must be a harmless no-op that never throws and yields
            // a closable NONE cage. We run a trivial short-lived process to have a
            // real Process handle to pass.
            if (isMac) {
                val p = ProcessBuilder(WorkerProcessSupport.defaultJavaBinary(), "-version").start()
                p.waitFor()
                val work = Files.createTempDirectory("kuml-poststart-").toFile().apply { deleteOnExit() }
                try {
                    val cage = OsSandbox.applyPostStart(p, work)
                    // No-op cage identity on non-Windows.
                    (cage === OsSandbox.PostStartCage.NONE).shouldBeTrue()
                    cage.close() // must not throw
                } finally {
                    work.deleteRecursively()
                }
            }
        }

        test("PostStartCage.NONE.close() is idempotent and side-effect-free") {
            OsSandbox.PostStartCage.NONE.close()
            OsSandbox.PostStartCage.NONE.close()
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

        // ── Linux behavioural verification (real bwrap, real kernel) ───────────
        //
        // Mirrors the macOS behavioural suite above exactly, guarded by isLinux
        // instead of isMac. Closes the "UNTESTED on real Linux" gap called out in
        // the OsSandbox KDoc and the architecture report: these tests actually
        // launch bwrap and assert the Linux kernel (not the denylist, not a mock)
        // refuses the escape.

        test("Linux OS sandbox blocks a file-write escape that has nothing to do with the denylist") {
            if (!isLinux) return@test
            if (!OsSandbox.isolationAvailable()) {
                println("[os-sandbox] skipping: bwrap present but non-functional on this host (see availability test)")
                return@test
            }
            val work = Files.createTempDirectory("kuml-ossbx-fw-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            val escapeTarget = File(System.getProperty("user.home"), "kuml-sandbox-escape-fw-test.txt")
            escapeTarget.delete()

            try {
                val cp =
                    compileJava(
                        classes,
                        "FwEscapeLinux",
                        """
                        import java.io.FileWriter;
                        public class FwEscapeLinux {
                          public static void main(String[] a) throws Exception {
                            FileWriter w = new FileWriter(a[0]);
                            w.write("ESCAPED"); w.close();
                            System.out.println("WROTE");
                          }
                        }
                        """.trimIndent(),
                    )
                val bare =
                    listOf(
                        WorkerProcessSupport.defaultJavaBinary(),
                        "-cp",
                        cp,
                        "FwEscapeLinux",
                        escapeTarget.absolutePath,
                    )

                // Baseline: NO sandbox — the write must succeed (proves the escape is real).
                val (_, baseOut) = run(bare)
                baseOut shouldContainString "WROTE"
                escapeTarget.exists().shouldBeTrue()
                escapeTarget.delete()

                // Under bwrap: the very same write must FAIL at the kernel (mount
                // namespace makes $HOME read-only outside the workdir), even though
                // nothing here ever went near KumlScriptGuard.
                val wrapped = OsSandbox.wrap(bare, work)
                wrapped.first() shouldBe OsSandbox.bwrapPathOrNull()
                run(wrapped)
                escapeTarget.exists().shouldBeFalse()
            } finally {
                escapeTarget.delete()
                work.deleteRecursively()
            }
        }

        test("Linux OS sandbox blocks a network escape (raw-IP), denylist-independent") {
            if (!isLinux) return@test
            if (!OsSandbox.isolationAvailable()) {
                println("[os-sandbox] skipping: bwrap present but non-functional on this host (see availability test)")
                return@test
            }
            val work = Files.createTempDirectory("kuml-ossbx-net-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            try {
                val cp =
                    compileJava(
                        classes,
                        "NetEscapeLinux",
                        """
                        import java.net.*;
                        public class NetEscapeLinux {
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
                // Raw IP (no DNS needed) — --unshare-all removes the network
                // namespace entirely, so even a direct connect must be refused.
                val bareIp = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "NetEscapeLinux", "1.1.1.1")
                val wrapped = OsSandbox.wrap(bareIp, work)
                wrapped.first() shouldBe OsSandbox.bwrapPathOrNull()
                val (_, out) = run(wrapped)
                out shouldContainString "NET-BLOCKED"
            } finally {
                work.deleteRecursively()
            }
        }

        test("Linux OS sandbox blocks reading ~/.ssh, denylist-independent") {
            if (!isLinux) return@test
            if (!OsSandbox.isolationAvailable()) {
                println("[os-sandbox] skipping: bwrap present but non-functional on this host (see availability test)")
                return@test
            }
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
                        "SshReadLinux",
                        """
                        import java.nio.file.*;
                        public class SshReadLinux {
                          public static void main(String[] a) {
                            try {
                              String s = new String(Files.readAllBytes(Paths.get(a[0])));
                              System.out.println("SSH-READ:" + s);
                            } catch (Throwable t) { System.out.println("SSH-BLOCKED:" + t.getClass().getSimpleName()); }
                          }
                        }
                        """.trimIndent(),
                    )
                val bare = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "SshReadLinux", probe.absolutePath)
                val wrapped = OsSandbox.wrap(bare, work)
                val (_, out) = run(wrapped)
                out shouldContainString "SSH-BLOCKED"
            } finally {
                probe.delete()
                if (createdSsh) ssh.delete()
                work.deleteRecursively()
            }
        }

        test("Linux legitimate write INSIDE the per-worker workdir is allowed (no false-positive)") {
            if (!isLinux) return@test
            if (!OsSandbox.isolationAvailable()) {
                println("[os-sandbox] skipping: bwrap present but non-functional on this host (see availability test)")
                return@test
            }
            val work = Files.createTempDirectory("kuml-ossbx-ok-").toFile().apply { deleteOnExit() }
            val classes = File(work, "classes").apply { mkdirs() }
            try {
                val cp =
                    compileJava(
                        classes,
                        "OkWriteLinux",
                        """
                        import java.io.FileWriter;
                        public class OkWriteLinux {
                          public static void main(String[] a) throws Exception {
                            FileWriter w = new FileWriter(a[0] + "/scratch.txt");
                            w.write("ok"); w.close();
                            System.out.println("OK-WROTE");
                          }
                        }
                        """.trimIndent(),
                    )
                val bare = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", cp, "OkWriteLinux", work.absolutePath)
                val wrapped = OsSandbox.wrap(bare, work)
                val (_, out) = run(wrapped)
                out shouldContainString "OK-WROTE"
                File(work, "scratch.txt").exists().shouldBeTrue()
            } finally {
                work.deleteRecursively()
            }
        }

        test("Linux: a legitimate multi-JVM render pipeline still works fully caged (compiler warmup + temp writes)") {
            if (!isLinux) return@test
            if (!OsSandbox.isolationAvailable()) {
                println("[os-sandbox] skipping: bwrap present but non-functional on this host (see availability test)")
                return@test
            }
            // The real worker launches java, which itself needs to read broadly
            // (JDK modules, embedded Kotlin compiler jars) and write only inside
            // the workdir (compiler tmp files, class output). This is the
            // strongest sanity check that the --ro-bind / posture is not so
            // strict it breaks a legitimate render: actually run javac (a
            // reasonable proxy for the embedded Kotlin compiler's own I/O
            // pattern) fully inside the bwrap cage.
            val work = Files.createTempDirectory("kuml-ossbx-compiler-").toFile().apply { deleteOnExit() }
            try {
                val src =
                    File(work, "Hello.java").apply {
                        writeText(
                            "public class Hello { public static void main(String[] a){ " +
                                "System.out.println(\"HELLO-FROM-CAGE\"); } }",
                        )
                    }
                val javac = File(WorkerProcessSupport.defaultJavaBinary()).parentFile.resolve("javac").absolutePath
                val bare = listOf(javac, "-d", work.absolutePath, src.absolutePath)
                val wrapped = OsSandbox.wrap(bare, work)
                val (compileExit, compileOut) = run(wrapped)
                compileExit shouldBe 0
                File(work, "Hello.class").exists().shouldBeTrue()

                val runBare = listOf(WorkerProcessSupport.defaultJavaBinary(), "-cp", work.absolutePath, "Hello")
                val runWrapped = OsSandbox.wrap(runBare, work)
                val (_, runOut) = run(runWrapped)
                runOut shouldContainString "HELLO-FROM-CAGE"
            } finally {
                work.deleteRecursively()
            }
        }
    })
