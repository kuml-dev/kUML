package dev.kuml.core.script

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * OS-native isolation for script-worker child processes (Wellen 4-5 of the
 * MCP-Sandbox architecture).
 *
 * Wellen 2-3 put every untrusted script in a **separate JVM** with a wall-clock
 * timeout, a heap cap, a minimal environment and a fixed argv — that contains
 * hangs, OOM and command injection. But a child JVM without an OS cage still
 * runs with the **full privileges of the desktop user**: if the regex denylist
 * (layer 1) is bypassed, the script can still read `~/.ssh`, open a socket to
 * exfiltrate it, or drop a launchd/cron persistence artefact. This layer closes
 * that gap by launching the worker inside an OS-enforced sandbox.
 *
 * ## Current platform coverage
 *
 *  - **macOS** (Welle 4, verified on-hardware) — `sandbox-exec` with a strict
 *    `.sb` profile ([MACOS_SANDBOX_PROFILE_RESOURCE]): deny-by-default,
 *    `network*` denied, writes confined to a per-worker temp dir, reads on the
 *    top secret stores denied. Empirically verified to block file-write to
 *    `$HOME`, DNS- and raw-IP network connects, and reads of `~/.ssh`, while
 *    letting legitimate DSL scripts (which need the embedded Kotlin compiler +
 *    temp writes) run unhindered.
 *  - **Linux** (Welle 5, **verified on real Linux**, 2026-07-04 — see the
 *    honesty note below) — `bwrap` (bubblewrap) with `--unshare-all` (no
 *    network, no PID/IPC/user namespaces shared), the whole root filesystem
 *    bind-mounted read-only, the per-worker temp dir bind-mounted read-write,
 *    a private `/tmp` tmpfs, `--die-with-parent`, and a private-tmpfs
 *    read-shadow over the existing entries of [SECRET_HOME_SUBPATHS]
 *    (defence-in-depth, added after real-kernel testing found the broad
 *    root read-bind alone left `~/.ssh` etc. readable — see the honesty
 *    note). This mirrors the macOS posture (broad read, no write outside the
 *    workdir, no network, secret-dir read denied) but enforces it via Linux
 *    namespaces instead of a policy filter. There is **no** additional
 *    seccomp-bpf filter in this wave (see the seccomp note below).
 *  - **Windows** (Welle 6, **UNTESTED on real Windows** — see the honesty note
 *    below) — a **Job Object** ([WindowsJobObjectSandbox]) caps per-process
 *    memory, forbids process spawning (`JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 1`) and
 *    kills the worker when the parent releases the job (`KILL_ON_JOB_CLOSE`).
 *    Unlike macOS/Linux this cannot be a command prefix — a Job Object is applied
 *    **after** the process starts — so on Windows [wrap] returns the command
 *    **unchanged** and the cage is installed by the **post-start hook**
 *    [applyPostStart]. **Network and tight filesystem confinement are NOT solved
 *    by the Job Object** (see [WindowsJobObjectSandbox]'s "open gaps"): the Job
 *    Object reliably contains memory + process-spawn + parent-death, but network
 *    egress and per-path FS confinement are documented gaps on this platform.
 *  - **Other** (BSD/Solaris/…) — not implemented. [wrap] returns the command
 *    unchanged and [isolationAvailable] is false, so the fail-closed policy in
 *    [WorkerProcessSupport] decides whether that is tolerated (see
 *    `KUML_MCP_SANDBOX_OS_ISOLATION`).
 *
 * ## ✅ Verified on real Linux (2026-07-04)
 *
 * The Linux `bwrap` command construction was originally implemented and only
 * unit-verified on a macOS development machine where `bwrap` does not exist
 * and Linux kernel namespaces cannot be exercised — the tests at that point
 * proved only that the **argument vector is constructed correctly**
 * ([bwrapCommandFor]), not that the resulting cage blocks anything at
 * runtime. This was closed out on a real Ubuntu 26.04 / kernel 7.0 host
 * (unprivileged user namespaces enabled, `bwrap` 0.11.1, not setuid) with the
 * same denylist-independent, real-kernel methodology used for macOS: a raw
 * compiled Java escape program (no kUML DSL, no [KumlScriptGuard]) launched
 * through the exact [wrap] path a worker uses.
 *
 * **Result — one real gap found and fixed, everything else confirmed:**
 *  - File-write escape to `$HOME`: **blocked** (kernel `EROFS`/permission
 *    denied via the read-only root bind), matching macOS.
 *  - Network escape (raw-IP connect, `--unshare-all` with no `--share-net`):
 *    **blocked**, matching macOS.
 *  - **`~/.ssh` read: initially NOT blocked.** The broad `--ro-bind / /`
 *    posture (chosen for the same reason as macOS's broad `file-read*`: the
 *    embedded Kotlin compiler + JDK read from scattered locations) makes
 *    every path under `/` — including `~/.ssh`, `~/.aws`, `~/.gnupg` —
 *    **readable**, whereas the macOS `.sb` profile has an explicit
 *    `(deny file-read* (subpath …))` override for exactly these directories
 *    that `bwrapCommandFor` had no equivalent for. **Fixed** by shadowing
 *    each *existing* entry of [SECRET_HOME_SUBPATHS] with a private, empty
 *    `--tmpfs` mount placed after the root ro-bind (later mounts shadow
 *    earlier ones for the same subtree, same precedence trick already used
 *    for the writable workdir `--bind`). Re-verified blocked after the fix.
 *  - Legitimate renders still work fully caged: a real `javac`-then-`java`
 *    round-trip (a reasonable proxy for the embedded Kotlin compiler's own
 *    I/O pattern: broad reads across the JDK, one write inside the workdir)
 *    completed successfully inside the cage — no false-positive denial.
 *
 * **Constraint discovered along the way with no macOS equivalent:** a bwrap
 * `--tmpfs DEST` target must already exist as a real directory — bwrap tries
 * to `mkdir` missing targets under the now-read-only root-bind parent, which
 * fails and aborts the *entire* bwrap invocation (`Read-only file system`),
 * not just that one mount. SBPL on macOS is inert on a nonexistent path;
 * bwrap is not. [bwrapCommandFor] therefore only shadows a secret directory
 * when [File.isDirectory] confirms it is actually present — a host without
 * e.g. `~/.aws` gets one fewer shadow, never a broken sandbox.
 *
 * **Still open** (tracked as follow-up, not blocking): the container/no-userns
 * degradation path (`best-effort` falling back to a plain child process when
 * `bwrap` cannot acquire `CAP_SYS_ADMIN`/unprivileged-userns) was not
 * exercised on this host, because unprivileged user namespaces are enabled
 * here by default. Seccomp-bpf remains deliberately unimplemented (see below).
 * Because of the real gap found above, the Linux default remains
 * **best-effort** (not promoted to `required`) until that container case is
 * also verified — see [modeFrom].
 *
 * ## ⚠️ Honesty note on the Windows path (Welle 6)
 *
 * Same situation, worse: the Windows Job-Object path in [WindowsJobObjectSandbox]
 * was written and compile-verified on **macOS**, where there is no `kernel32.dll`
 * and not a single Win32 call can run. Struct layouts, flag constants and the
 * whole CreateJobObject → SetInformationJobObject → AssignProcessToJobObject flow
 * are transcribed from documented Win32 but **run nowhere in this build**. The
 * tests here verify only mode/platform *logic* and that [wrap] leaves the Windows
 * command unchanged — they do **not** exercise any Job Object. In addition, even
 * once verified, the Windows cage is **weaker than macOS/Linux**: it does not
 * block network egress and does not confine the filesystem to the workdir (see
 * [WindowsJobObjectSandbox]). **This path requires CI verification on real Windows
 * before it is relied upon in production**, and Windows stays best-effort by
 * default (see [modeFrom]).
 *
 * ## Why no seccomp-bpf in Welle 5
 *
 * The architecture note lists `seccomp-bpf` as an *additional* syscall-level
 * restriction beyond bwrap's namespace isolation. It is deliberately **not**
 * implemented here. A misconfigured seccomp filter is a footgun: the JVM issues
 * a large, version- and GC-dependent set of syscalls during startup (e.g.
 * `clone3`, `rseq`, `membarrier`, `io_uring_setup`, various `*at` variants), and
 * a filter that forgets any of them makes the JVM die with `SIGSYS` **before it
 * can even run the script** — indistinguishable from a sandbox bug, and
 * impossible to tune correctly without iterating on a real Linux kernel, which
 * this machine cannot do. Namespace isolation via bwrap already denies the three
 * high-value goals (network, write-outside-workdir, secret-read) at the kernel;
 * adding an unverified seccomp BPF program would trade a real security gain for a
 * real risk of breaking every legitimate render. A seccomp layer is a sound
 * *future* enhancement but must be developed and tuned against real Linux CI.
 *
 * V0.23.3 — Wellen 4-6.
 */
internal object OsSandbox {
    /** Classpath resource holding the macOS seatbelt profile. */
    const val MACOS_SANDBOX_PROFILE_RESOURCE: String = "/dev/kuml/core/script/sandbox/kuml-worker.macos.sb"

    /** Back-compat alias kept for existing references / tests. */
    @Deprecated("Use MACOS_SANDBOX_PROFILE_RESOURCE", ReplaceWith("MACOS_SANDBOX_PROFILE_RESOURCE"))
    const val SANDBOX_PROFILE_RESOURCE: String = MACOS_SANDBOX_PROFILE_RESOURCE

    /** `sandbox-exec` lives here on every macOS; a fixed absolute path (no PATH lookup). */
    const val SANDBOX_EXEC_PATH: String = "/usr/bin/sandbox-exec"

    /**
     * Candidate absolute paths for the `bwrap` (bubblewrap) binary on Linux, in
     * preference order. Distros put it in `/usr/bin` (Debian/Ubuntu/Fedora) or
     * `/usr/local/bin` (source builds); some sandboxed/immutable distros symlink
     * it under `/bin`. We resolve a fixed absolute path (no PATH lookup, so the
     * untrusted environment cannot shadow it).
     */
    val BWRAP_CANDIDATE_PATHS: List<String> =
        listOf("/usr/bin/bwrap", "/usr/local/bin/bwrap", "/bin/bwrap")

    /**
     * Home-relative directories holding the highest-value secrets a script
     * could try to exfiltrate. Mirrors the macOS profile's defence-in-depth
     * `(deny file-read* (subpath …))` list ([MACOS_SANDBOX_PROFILE_RESOURCE])
     * as closely as the two platforms allow — `~/Library/Keychains` is
     * macOS-only and has no Linux equivalent, so it is omitted here.
     */
    val SECRET_HOME_SUBPATHS: List<String> = listOf(".ssh", ".aws", ".gnupg", ".config/gcloud")

    /**
     * Per-process committed-memory cap for the Windows Job Object, in bytes.
     * Sized above the worker heap ([WorkerProcessSupport] uses `-Xmx256m`) plus
     * JVM native overhead (metaspace, code cache, thread stacks, embedded Kotlin
     * compiler): 768 MiB leaves generous headroom for a legitimate render while
     * still hard-stopping a native-memory blow-up the `-Xmx` cap would miss.
     */
    const val WINDOWS_JOB_MAX_PROCESS_MEMORY_BYTES: Long = 768L * 1024 * 1024

    /**
     * The three OS platforms whose isolation is planned. `OTHER` covers anything
     * unrecognised (BSDs, Solaris, …) and is treated like an unimplemented
     * platform.
     */
    enum class Platform { MAC, LINUX, WINDOWS, OTHER }

    /**
     * Isolation strictness for the current host, from
     * `KUML_MCP_SANDBOX_OS_ISOLATION`:
     *
     *  - `required` — a worker MUST be OS-isolated. If OS isolation is
     *    unavailable (unimplemented platform, or the OS mechanism missing —
     *    `sandbox-exec` on macOS, `bwrap` on Linux), worker launch **fails
     *    closed**: no ungated child is ever started. This is the default **on
     *    macOS**, where `sandbox-exec` is present on every host.
     *  - `best-effort` — use OS isolation when available, otherwise fall back to
     *    the plain (Wellen 2-3) child process. This is the default on Linux
     *    (because `bwrap` availability is inconsistent across distros and
     *    especially inside restrictive containers without `CAP_SYS_ADMIN` /
     *    unprivileged-userns — see the report) and on Windows/other (isolation
     *    not yet implemented). An operator can force `required` there too, which
     *    will fail closed until the cage can be applied.
     */
    enum class Mode { REQUIRED, BEST_EFFORT }

    const val ENV_OS_ISOLATION: String = "KUML_MCP_SANDBOX_OS_ISOLATION"
    const val MODE_REQUIRED: String = "required"
    const val MODE_BEST_EFFORT: String = "best-effort"

    /** Detected host platform. */
    val platform: Platform = detectPlatform(System.getProperty("os.name"))

    /** Testable OS-name → [Platform] classifier. */
    fun detectPlatform(osName: String?): Platform {
        val n = osName.orEmpty().lowercase()
        return when {
            n.contains("mac") || n.contains("darwin") -> Platform.MAC
            n.contains("windows") -> Platform.WINDOWS
            n.contains("linux") || n.contains("nux") -> Platform.LINUX
            else -> Platform.OTHER
        }
    }

    /** Resolved isolation mode from the environment, with a platform-sensitive default. */
    fun mode(): Mode = modeFrom(System.getenv(ENV_OS_ISOLATION), platform)

    /**
     * Testable resolver. Default is `required` **only on macOS** (where the OS
     * mechanism is present on every host and verified); everywhere else the
     * default is `best-effort`, so a missing `bwrap` (Linux) or an unimplemented
     * platform (Windows/other) does not silently break rendering — but an
     * operator can opt into `required` to fail closed.
     */
    fun modeFrom(
        raw: String?,
        platform: Platform,
    ): Mode =
        when (raw?.trim()?.lowercase()) {
            MODE_REQUIRED -> Mode.REQUIRED
            MODE_BEST_EFFORT -> Mode.BEST_EFFORT
            else -> if (platform == Platform.MAC) Mode.REQUIRED else Mode.BEST_EFFORT
        }

    /**
     * True if this host can actually enforce OS isolation right now:
     *  - macOS: an executable `sandbox-exec` and an extractable profile.
     *  - Linux: an executable `bwrap` on disk. (Whether `bwrap` will *succeed*
     *    at runtime — unprivileged userns available, `CAP_SYS_ADMIN` in a
     *    container, etc. — cannot be answered without actually running it; the
     *    fail-closed launch path treats a bwrap launch failure as a SANDBOX
     *    error, so a false-positive here degrades to a clear per-call failure
     *    under `required`, not to an un-caged worker.)
     */
    fun isolationAvailable(): Boolean =
        when (platform) {
            Platform.MAC -> File(SANDBOX_EXEC_PATH).canExecute() && macProfileFileOrNull() != null
            Platform.LINUX -> bwrapPathOrNull() != null
            // Windows: the Job Object cage is applied post-start (not via wrap), so
            // "available" means the JNA runtime is loadable. Whether the actual
            // CreateJobObject/AssignProcessToJobObject calls succeed can only be
            // known when [applyPostStart] runs; a failure there is handled
            // fail-closed by the launcher under `required`.
            Platform.WINDOWS -> jnaRuntimeLoadable()
            else -> false
        }

    /**
     * True if the JNA runtime classes needed for the Windows Job Object cage are
     * on the classpath and loadable. Uses reflection so this compiles and returns
     * a safe answer on every OS. (On non-Windows this is irrelevant — the value is
     * only consulted from the Windows branch above.)
     */
    private fun jnaRuntimeLoadable(): Boolean =
        try {
            Class.forName("com.sun.jna.Native")
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Wraps a bare worker launch [command] (e.g. `[java, -Xmx…, -cp, …, Main]`)
     * so it runs inside the OS sandbox, using [workDir] as the sole writable
     * location. Returns the possibly-wrapped command, or throws
     * [SandboxUnavailableException] when the resolved [Mode] is `required` but
     * OS isolation cannot be applied — the caller must then fail closed and MUST
     * NOT launch the bare command.
     *
     * On a platform/mode where OS isolation is not applied under `best-effort`,
     * the command is returned unchanged.
     *
     * **Windows is special**: its Job-Object cage cannot be a command prefix (it
     * is applied *after* `ProcessBuilder.start()`), so [wrap] returns the Windows
     * command **unchanged** and does not fail-closed here — the cage, and the
     * fail-closed decision, live in [applyPostStart]. This is the architecture
     * difference Welle 6 had to introduce: macOS/Linux cage *before* exec via
     * argv, Windows cages *after* start via a handle.
     */
    fun wrap(
        command: List<String>,
        workDir: File,
    ): List<String> {
        val mode = mode()
        // Canonicalize the workdir once: the enforcing kernel (seatbelt on macOS,
        // the mount namespace on Linux) matches the *real* path (system temp is
        // often symlinked — /var/folders/… → /private/var/folders/… on macOS,
        // /tmp → sometimes elsewhere on Linux), so the sandbox param / bind target
        // must be canonical or a legitimate write gets denied.
        val canonicalWorkDir = runCatching { workDir.canonicalPath }.getOrDefault(workDir.absolutePath)

        return when (platform) {
            Platform.MAC -> wrapMac(command, canonicalWorkDir, mode)
            Platform.LINUX -> wrapLinux(command, canonicalWorkDir, mode)
            Platform.WINDOWS -> {
                // The Job-Object cage is applied post-start (see applyPostStart),
                // not as a command prefix. Return the command unchanged; do NOT
                // fail-closed here — the required/best-effort decision is made in
                // applyPostStart once the cage has actually been attempted.
                command
            }
            else -> {
                // Truly unsupported platform (BSD/Solaris/…): fail closed only if
                // the operator explicitly demanded `required`.
                if (mode == Mode.REQUIRED) {
                    throw SandboxUnavailableException(
                        "OS-level sandbox isolation is required but not implemented on this platform " +
                            "(${System.getProperty("os.name")}). Set $ENV_OS_ISOLATION=$MODE_BEST_EFFORT to allow " +
                            "process/heap/timeout containment without an OS cage, or run on macOS/Linux/Windows.",
                    )
                }
                command
            }
        }
    }

    /**
     * A cage installed *after* the worker process started. Currently only the
     * Windows Job Object uses this; on macOS/Linux the cage is already in argv, so
     * [applyPostStart] returns a no-op handle. The caller must [PostStartCage.close]
     * it when the worker is done (on Windows, closing the job handle kills the
     * caged process via `KILL_ON_JOB_CLOSE`).
     */
    interface PostStartCage {
        fun close()

        companion object {
            /** A cage that holds nothing — used on platforms that cage via argv. */
            val NONE: PostStartCage =
                object : PostStartCage {
                    override fun close() = Unit
                }
        }
    }

    /**
     * Installs the OS cage that can only be applied *after* the process exists.
     *
     *  - **Windows**: creates a Job Object (memory cap + single-process +
     *    kill-on-close) and assigns the running [process] into it. If the cage
     *    cannot be installed and the resolved [Mode] is `required`, throws
     *    [SandboxUnavailableException] so the caller fails closed (and MUST kill
     *    the un-caged process). Under `best-effort`, a failure returns
     *    [PostStartCage.NONE] and the un-caged process is tolerated.
     *  - **macOS/Linux/other**: no-op ([PostStartCage.NONE]) — the cage (if any)
     *    was already applied via [wrap].
     *
     * @param process the just-started worker process.
     * @param workDir the per-worker writable dir (reserved for future ACL-based
     *   Windows FS confinement; currently unused by the Job Object path).
     */
    fun applyPostStart(
        process: Process,
        @Suppress("UNUSED_PARAMETER") workDir: File,
    ): PostStartCage {
        if (platform != Platform.WINDOWS) return PostStartCage.NONE
        val mode = mode()
        val handle =
            runCatching {
                WindowsJobObjectSandbox.applyJobObject(
                    pid = process.pid(),
                    maxProcessMemoryBytes = WINDOWS_JOB_MAX_PROCESS_MEMORY_BYTES,
                )
            }.getOrNull()
        if (handle == null) {
            if (mode == Mode.REQUIRED) {
                throw SandboxUnavailableException(
                    "OS-level sandbox isolation is required but the Windows Job Object cage could not be " +
                        "installed on the worker process. Refusing to run an un-caged worker. Set " +
                        "$ENV_OS_ISOLATION=$MODE_BEST_EFFORT to allow process/heap/timeout containment without " +
                        "an OS cage.",
                )
            }
            return PostStartCage.NONE
        }
        return object : PostStartCage {
            override fun close() = handle.close()
        }
    }

    // ── macOS: sandbox-exec (Welle 4) ─────────────────────────────────────────

    private fun wrapMac(
        command: List<String>,
        canonicalWorkDir: String,
        mode: Mode,
    ): List<String> {
        val sandboxExec = File(SANDBOX_EXEC_PATH)
        val profile = macProfileFileOrNull()
        if (!sandboxExec.canExecute() || profile == null) {
            if (mode == Mode.REQUIRED) {
                throw SandboxUnavailableException(
                    "OS-level sandbox isolation is required but sandbox-exec is unavailable or the sandbox " +
                        "profile could not be prepared. Refusing to launch an un-caged worker.",
                )
            }
            return command
        }
        // sandbox-exec -D WORKDIR=<workdir> -D USERHOME=<home> -f <profile> <command…>
        return buildList {
            add(SANDBOX_EXEC_PATH)
            add("-D")
            add("WORKDIR=$canonicalWorkDir")
            add("-D")
            add("USERHOME=${userHome()}")
            add("-f")
            add(profile.absolutePath)
            addAll(command)
        }
    }

    // ── Linux: bubblewrap (Welle 5, UNTESTED on real Linux) ───────────────────

    private fun wrapLinux(
        command: List<String>,
        canonicalWorkDir: String,
        mode: Mode,
    ): List<String> {
        val bwrap = bwrapPathOrNull()
        if (bwrap == null) {
            if (mode == Mode.REQUIRED) {
                throw SandboxUnavailableException(
                    "OS-level sandbox isolation is required but `bwrap` (bubblewrap) was not found on this Linux " +
                        "host (looked in $BWRAP_CANDIDATE_PATHS). Install bubblewrap, or set " +
                        "$ENV_OS_ISOLATION=$MODE_BEST_EFFORT to allow process/heap/timeout containment without an " +
                        "OS cage. Refusing to launch an un-caged worker.",
                )
            }
            return command
        }
        return bwrapCommandFor(bwrap, command, canonicalWorkDir)
    }

    /**
     * Builds the full `bwrap … -- <command>` argument vector. Extracted and
     * `internal` so it can be **unit-verified on any OS** (macOS included) — the
     * test asserts the exact argv without needing a Linux kernel.
     *
     * Posture (mirrors the macOS profile: broad read, no write outside workdir,
     * no network):
     *  - `--unshare-all` — new user/pid/ipc/uts/cgroup **and network** namespaces.
     *    Because there is no `--share-net`, the worker has **no network** at all
     *    (loopback only, and even that is isolated). This is the Linux equivalent
     *    of the macOS `(deny network*)`.
     *  - `--die-with-parent` — if the parent (the MCP server / pool) dies, the
     *    sandboxed worker is killed too; no orphaned caged JVMs.
     *  - `--ro-bind / / ` — the **entire root filesystem read-only**. This gives
     *    the embedded Kotlin compiler + JDK the broad read access they need from
     *    scattered classpath/JDK locations (same rationale as the macOS broad
     *    `file-read*`), while making every path outside the workdir
     *    **unwritable** at the kernel. A single ro-bind of `/` is far more robust
     *    across JDK/jlink-layout and dependency changes than an exact per-jar
     *    read allowlist.
     *  - `--dev /dev` — a minimal private `/dev` (null, zero, random, urandom,
     *    …). Replaces the ro-bound `/dev` so the JVM's `/dev/random` etc. work
     *    without exposing the host's real device tree.
     *  - `--proc /proc` — a private `/proc` for the new PID namespace (the JVM
     *    reads `/proc/self/…` at startup).
     *  - `--tmpfs /tmp` — a private, empty, writable `/tmp` (an in-namespace
     *    tmpfs, not the host's). Any tool honouring the global `/tmp` writes into
     *    throwaway memory, not the host disk.
     *  - `--bind <workDir> <workDir>` — the per-worker temp dir bind-mounted
     *    read-write at the **same absolute path** the parent handed the JVM via
     *    `-Djava.io.tmpdir` / `$TMPDIR`. This is the single writable location,
     *    the Linux equivalent of the macOS `(allow file-write* (subpath WORKDIR))`.
     *    The bind is placed **after** the `/`-ro-bind so it takes precedence
     *    (later binds shadow earlier ones for the same subtree).
     *  - `--chdir <workDir>` — start the JVM with the workdir as CWD so any
     *    relative scratch path also lands in the writable cage.
     *  - `--clearenv` then `--setenv PATH …` / `--setenv TMPDIR …` — bwrap starts
     *    from an empty environment; we restore only the two vars the worker needs
     *    (the parent already clears its own env in [WorkerProcessSupport], this is
     *    belt-and-braces so nothing leaks through bwrap either). TMPDIR points at
     *    the writable workdir.
     *  - `--tmpfs <homeDir>/<secret>` (one per existing entry in
     *    [SECRET_HOME_SUBPATHS]) — defence-in-depth: shadows the highest-value
     *    secret directories with an empty private tmpfs so the broad
     *    `--ro-bind / /` read access above cannot see their contents. This is
     *    the bwrap equivalent of the macOS profile's `(deny file-read*
     *    (subpath …))` overrides. **Linux-specific constraint with no macOS
     *    equivalent**: a `--tmpfs DEST` target must already exist as a real
     *    directory, because bwrap creates missing mount points by `mkdir`ing
     *    them under the (by then read-only, via the `/`-ro-bind above) parent
     *    — which fails and aborts the *entire* bwrap launch, not just that one
     *    mount (empirically verified: an unconditional `--tmpfs` on a missing
     *    path fails with "Read-only file system" and the worker never starts).
     *    SBPL on macOS is inert on a nonexistent path; bwrap is not — so each
     *    secret directory is only shadowed when [File.isDirectory] confirms it
     *    is actually there. A host without e.g. `~/.aws` simply gets one fewer
     *    shadow, never a broken sandbox.
     */
    fun bwrapCommandFor(
        bwrapPath: String,
        command: List<String>,
        canonicalWorkDir: String,
        homeDir: String = userHome(),
    ): List<String> {
        val path = System.getenv("PATH") ?: "/usr/bin:/bin"
        return buildList {
            add(bwrapPath)
            add("--unshare-all")
            add("--die-with-parent")
            // Whole root read-only → broad read, zero write outside the workdir.
            add("--ro-bind")
            add("/")
            add("/")
            // Defence-in-depth: shadow existing secret directories (see KDoc).
            for (subpath in SECRET_HOME_SUBPATHS) {
                val dir = File(homeDir, subpath)
                if (dir.isDirectory) {
                    add("--tmpfs")
                    add(dir.absolutePath)
                }
            }
            // Minimal private /dev and /proc for the new namespaces.
            add("--dev")
            add("/dev")
            add("--proc")
            add("/proc")
            // Private throwaway /tmp (never the host's real /tmp).
            add("--tmpfs")
            add("/tmp")
            // The single writable location, at the same absolute path the JVM uses.
            add("--bind")
            add(canonicalWorkDir)
            add(canonicalWorkDir)
            add("--chdir")
            add(canonicalWorkDir)
            // Empty env, then restore only what the JVM needs.
            add("--clearenv")
            add("--setenv")
            add("PATH")
            add(path)
            add("--setenv")
            add("TMPDIR")
            add(canonicalWorkDir)
            // Separator: everything after `--` is the program + its argv.
            add("--")
            addAll(command)
        }
    }

    /** First existing, executable bwrap candidate path, or null. */
    fun bwrapPathOrNull(): String? = BWRAP_CANDIDATE_PATHS.firstOrNull { File(it).canExecute() }

    private fun userHome(): String = System.getProperty("user.home") ?: "/var/empty"

    /**
     * Extracts the bundled macOS `.sb` profile to a stable per-JVM temp file
     * (once) and returns it, or null if extraction fails. The file is created
     * with owner-only permissions and marked delete-on-exit. (Linux/bwrap needs
     * no such profile file — the cage is expressed entirely as bwrap argv.)
     */
    @Volatile private var cachedProfile: File? = null

    @Synchronized
    private fun macProfileFileOrNull(): File? {
        cachedProfile?.let { if (it.isFile) return it }
        return try {
            val bytes =
                OsSandbox::class.java.getResourceAsStream(MACOS_SANDBOX_PROFILE_RESOURCE)?.use { it.readBytes() }
                    ?: return null
            val perms = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            val path = Files.createTempFile("kuml-worker-", ".sb", perms)
            val file = path.toFile()
            file.writeBytes(bytes)
            file.deleteOnExit()
            cachedProfile = file
            file
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Thrown when the resolved OS-isolation [OsSandbox.Mode] is `required` but the
 * host cannot enforce it. Callers translate this into a
 * [FailureKind.SANDBOX] result — never a fall-through to an un-caged worker.
 */
internal class SandboxUnavailableException(
    message: String,
) : Exception(message)
