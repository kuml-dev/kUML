package dev.kuml.core.script

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows OS-native isolation of a script-worker child process (Welle 6 of the
 * MCP-Sandbox architecture), implemented with a **Job Object** (and — where the
 * host permits — a **restricted primary token**), bound to Win32 via JNA.
 *
 * ## ⚠️⚠️ HONESTY NOTE — UNTESTED ON REAL WINDOWS ⚠️⚠️
 *
 * This entire file was written and compile-verified on a **macOS** machine. There
 * is **no** Windows kernel, no `kernel32.dll`, and no way to execute a single one
 * of the Win32 calls below here. The JNA bindings, the struct layouts, the flag
 * constants and the overall CreateJobObject → SetInformationJobObject →
 * AssignProcessToJobObject flow are transcribed from the documented Win32 API to
 * the best of the author's knowledge, but **not a single line has run against a
 * real Windows kernel.** Field offsets in the `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`
 * struct, the exact HANDLE marshalling, and the interaction with the JVM's own
 * process launch are all plausible-but-unverified. **This path MUST be verified
 * on real Windows CI before it is relied upon in production.** Until then it is
 * `best-effort` by default on Windows (see [OsSandbox.modeFrom]).
 *
 * ## Why a POST-START hook, not a command wrapper
 *
 * macOS (`sandbox-exec`) and Linux (`bwrap`) wrap the launch as a **command
 * prefix** — a launcher binary that execs `java` inside an already-configured
 * cage. Windows has **no equivalent CLI wrapper**. A Job Object is applied
 * **after** the process exists: you create the job, set its limits, then
 * [Kernel32Ext.AssignProcessToJobObject] the already-started process into it. So
 * [OsSandbox.wrap] returns the Windows command **unchanged**, and this class is
 * invoked as a **post-start hook** ([OsSandbox.applyPostStart]) right after
 * `ProcessBuilder.start()`.
 *
 * ### Inherent race (documented, not hidden)
 *
 * Because the assignment happens *after* the child is already running, there is a
 * tiny window between process creation and [AssignProcessToJobObject] during which
 * the child is NOT yet governed by the job. A child that forks a grandchild in
 * that window could — in theory — escape the `JOB_OBJECT_LIMIT_ACTIVE_PROCESS`
 * cap. The fully race-free fix is to launch the process with `CREATE_SUSPENDED`
 * via `CreateProcessW`, assign it to the job, then `ResumeThread` — but that means
 * abandoning `ProcessBuilder` and reimplementing launch/IO-redirection natively,
 * which is a much larger and riskier change. For a JVM child that needs seconds
 * of compiler warmup before it can do anything, the practical exposure of this
 * race is negligible, but it is a real theoretical gap and is called out as such.
 *
 * ## What the Job Object enforces (memory + process-count + kill-on-close)
 *
 *  - `JOB_OBJECT_LIMIT_PROCESS_MEMORY` — a hard per-process committed-memory cap
 *    (mirrors the `-Xmx` heap cap, but at the OS level, covering native memory too).
 *  - `JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 1` — the job may contain at most ONE
 *    active process. Any attempt by the worker to spawn a child fails → this is
 *    the anti-fork-bomb / anti-process-spawn control (analogous to the Linux
 *    PID-namespace + `--unshare-all` posture).
 *  - `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` — when the parent closes the job handle
 *    (or dies, releasing the handle), every process in the job is killed. This is
 *    the Windows equivalent of `--die-with-parent`; no orphaned caged JVMs.
 *
 * ## What this path does NOT (fully) solve — honest gaps
 *
 *  - **Network is NOT blocked by the Job Object.** Unlike macOS `(deny network*)`
 *    and Linux `--unshare-all`, a Job Object has no network-egress control.
 *    `JOBOBJECT_BASIC_UI_RESTRICTIONS` governs *UI/USER* objects (clipboard,
 *    desktop, global atoms, …), NOT sockets — using it for "network restriction"
 *    would be security theatre. Real per-process network blocking on Windows needs
 *    a **WFP filter** or a per-process **Windows Firewall rule** (`netsh advfirewall`
 *    matching the child's image path / PID), which is: (a) admin-only for reliable
 *    rules, (b) racy against PID reuse, (c) impossible to develop/verify without a
 *    real Windows host. It is therefore **deliberately NOT implemented** in this
 *    wave and documented as an open gap. **On Windows, network exfiltration is
 *    currently mitigated only by the layer-1 denylist + the restricted token's
 *    reduced privileges — NOT by an OS network cage.**
 *  - **Filesystem confinement is via the restricted token, not a namespace.**
 *    Windows has no mount-namespace equivalent. The best available OS control is a
 *    **restricted primary token** ([CreateRestrictedToken] with
 *    `DISABLE_MAX_PRIVILEGE`, which strips all privileges from the token and marks
 *    all groups deny-only), which reduces what the worker can touch, plus (in
 *    principle) explicit **deny ACLs** on the secret directories. A restricted
 *    token alone does NOT confine reads/writes to the workdir the way `bwrap
 *    --bind` does — it only removes privileges and elevated group access. Tight
 *    per-path write confinement would require per-directory ACL programming
 *    (SetNamedSecurityInfo on the workdir + deny-ACEs on `%USERPROFILE%\.ssh`
 *    etc.), which is extensive and, again, unverifiable here. This wave sets up the
 *    restricted-token launch *scaffold* but marks tight FS confinement as an open
 *    gap (see [restrictedTokenSupported]).
 *
 * In short: **this Windows wave reliably caps memory and process-spawning and
 * kills-on-parent-death (once verified on real Windows). It does NOT provide the
 * network + filesystem cage that macOS/Linux do.** That asymmetry is stated
 * plainly rather than papered over.
 *
 * V0.23.3 — Welle 6 (UNTESTED on real Windows).
 */
internal object WindowsJobObjectSandbox {
    // ── Win32 constants (from winnt.h / jobapi2.h) ─────────────────────────────

    /** JOBOBJECTINFOCLASS.JobObjectExtendedLimitInformation. */
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: Int = 9

    /** JOBOBJECT_BASIC_LIMIT_INFORMATION.LimitFlags bits. */
    private const val JOB_OBJECT_LIMIT_ACTIVE_PROCESS: Int = 0x00000008
    private const val JOB_OBJECT_LIMIT_PROCESS_MEMORY: Int = 0x00000100
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: Int = 0x00002000

    /** DesiredAccess flags for OpenProcess (winnt.h). */
    private const val PROCESS_SET_QUOTA: Int = 0x0100
    private const val PROCESS_TERMINATE: Int = 0x0001

    /**
     * Applies a Job Object cage to an already-started Windows process.
     *
     * @param pid the child PID (from `Process.pid()`).
     * @param maxProcessMemoryBytes hard per-process committed-memory cap.
     * @return a [WindowsJobHandle] the caller must keep alive for the lifetime of
     *   the worker and [WindowsJobHandle.close] when the worker is done (closing it
     *   kills the caged process via `KILL_ON_JOB_CLOSE`), or `null` if the cage
     *   could NOT be applied (the caller decides fail-closed vs. best-effort).
     */
    fun applyJobObject(
        pid: Long,
        maxProcessMemoryBytes: Long,
    ): WindowsJobHandle? {
        val k32 = kernel32OrNull() ?: return null
        val ext = kernel32ExtOrNull() ?: return null

        // 1. Open a handle to the already-running child with the rights we need.
        val access = PROCESS_SET_QUOTA or PROCESS_TERMINATE
        val processHandle: WinNT.HANDLE =
            k32.OpenProcess(access, false, pid.toInt())
                ?: return null
        if (Pointer.nativeValue(processHandle.pointer) == 0L) return null

        var jobHandle: WinNT.HANDLE? = null
        try {
            // 2. Create an anonymous job object.
            jobHandle = ext.CreateJobObjectW(null, null)
            if (jobHandle == null || Pointer.nativeValue(jobHandle.pointer) == 0L) {
                k32.CloseHandle(processHandle)
                return null
            }

            // 3. Configure limits: memory cap + single-process + kill-on-close.
            val info = JobObjectExtendedLimitInformation()
            info.BasicLimitInformation.LimitFlags =
                JOB_OBJECT_LIMIT_PROCESS_MEMORY or
                JOB_OBJECT_LIMIT_ACTIVE_PROCESS or
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            info.BasicLimitInformation.ActiveProcessLimit = 1
            info.ProcessMemoryLimit = BaseTSD.SIZE_T(maxProcessMemoryBytes)
            info.write()

            val ok =
                ext.SetInformationJobObject(
                    jobHandle,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                    info.pointer,
                    info.size(),
                )
            if (!ok) {
                ext.CloseHandleSafe(jobHandle)
                k32.CloseHandle(processHandle)
                return null
            }

            // 4. Assign the running process into the job.
            val assigned = ext.AssignProcessToJobObject(jobHandle, processHandle)
            // We no longer need the process handle after assignment; the job holds
            // its own reference to the process.
            k32.CloseHandle(processHandle)
            if (!assigned) {
                ext.CloseHandleSafe(jobHandle)
                return null
            }

            return WindowsJobHandle(jobHandle, ext)
        } catch (_: Throwable) {
            runCatching { jobHandle?.let { ext.CloseHandleSafe(it) } }
            runCatching { k32.CloseHandle(processHandle) }
            return null
        }
    }

    /**
     * Whether a restricted primary token *could* be created on this host. The
     * restricted-token launch requires replacing `ProcessBuilder.start()` with a
     * native `CreateProcessAsUserW`/`CreateProcessW` using the restricted token,
     * which is NOT wired up in this wave (it would mean abandoning ProcessBuilder's
     * IO redirection). This probe exists so the scaffold is honest about what is
     * and is not active: currently always returns false, i.e. **the restricted
     * token is documented and scaffolded but NOT applied** — only the Job Object is
     * active. See the class KDoc's "open gaps" section.
     */
    fun restrictedTokenSupported(): Boolean = false

    /** A live job-object handle owning one caged process. Close to kill+release. */
    class WindowsJobHandle internal constructor(
        private val jobHandle: WinNT.HANDLE,
        private val ext: Kernel32Ext,
    ) {
        @Volatile private var closed = false

        /** Closes the job handle → kills the caged process (KILL_ON_JOB_CLOSE). Idempotent. */
        fun close() {
            if (closed) return
            closed = true
            runCatching { ext.CloseHandleSafe(jobHandle) }
        }
    }

    // ── JNA plumbing ───────────────────────────────────────────────────────────

    private fun kernel32OrNull(): Kernel32? = runCatching { Kernel32.INSTANCE }.getOrNull()

    private fun kernel32ExtOrNull(): Kernel32Ext? =
        runCatching {
            Native.load("kernel32", Kernel32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()

    /**
     * The Job-Object subset of `kernel32.dll` that jna-platform's [Kernel32] does
     * not expose. Declared here as its own [StdCallLibrary]. Compiles on any OS
     * (these are plain JNA declarations); only *bound* to the DLL at runtime via
     * [Native.load], which can only succeed on Windows.
     */
    @Suppress("FunctionName") // JNA binds by exact Win32 export name — camelCase would break the native call.
    interface Kernel32Ext : StdCallLibrary {
        /** CreateJobObjectW(lpJobAttributes, lpName) → job handle (or NULL). */
        fun CreateJobObjectW(
            lpJobAttributes: Pointer?,
            lpName: String?,
        ): WinNT.HANDLE?

        /** SetInformationJobObject(hJob, class, pInfo, cbInfo) → BOOL. */
        fun SetInformationJobObject(
            hJob: WinNT.HANDLE,
            jobObjectInformationClass: Int,
            lpJobObjectInformation: Pointer,
            cbJobObjectInformationLength: Int,
        ): Boolean

        /** AssignProcessToJobObject(hJob, hProcess) → BOOL. */
        fun AssignProcessToJobObject(
            hJob: WinNT.HANDLE,
            hProcess: WinNT.HANDLE,
        ): Boolean

        /** CloseHandle wrapper on this same library (BOOL). */
        fun CloseHandle(hObject: WinNT.HANDLE): Boolean

        /** Non-throwing convenience used from cleanup paths. */
        fun CloseHandleSafe(hObject: WinNT.HANDLE): Boolean = runCatching { CloseHandle(hObject) }.getOrDefault(false)
    }

    // ── Win32 structs (winnt.h) ─────────────────────────────────────────────────

    /**
     * JOBOBJECT_BASIC_LIMIT_INFORMATION. Field order and types transcribed from
     * winnt.h. `LARGE_INTEGER` maps to a 64-bit field; the two `SIZE_T` (Affinity)
     * are pointer-sized. **Layout unverified on real Windows.**
     */
    @Structure.FieldOrder(
        "PerProcessUserTimeLimit",
        "PerJobUserTimeLimit",
        "LimitFlags",
        "MinimumWorkingSetSize",
        "MaximumWorkingSetSize",
        "ActiveProcessLimit",
        "Affinity",
        "PriorityClass",
        "SchedulingClass",
    )
    @Suppress("PropertyName") // JNA @Structure.FieldOrder maps by exact winnt.h field name — layout would break otherwise.
    open class JobObjectBasicLimitInformation : Structure() {
        @JvmField var PerProcessUserTimeLimit: Long = 0

        @JvmField var PerJobUserTimeLimit: Long = 0

        @JvmField var LimitFlags: Int = 0

        @JvmField var MinimumWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)

        @JvmField var MaximumWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)

        @JvmField var ActiveProcessLimit: Int = 0

        @JvmField var Affinity: BaseTSD.ULONG_PTR = BaseTSD.ULONG_PTR(0)

        @JvmField var PriorityClass: Int = 0

        @JvmField var SchedulingClass: Int = 0
    }

    /** IO_COUNTERS (winnt.h) — six ULONGLONG counters; unused by us but part of the extended struct. */
    @Structure.FieldOrder(
        "ReadOperationCount",
        "WriteOperationCount",
        "OtherOperationCount",
        "ReadTransferCount",
        "WriteTransferCount",
        "OtherTransferCount",
    )
    @Suppress("PropertyName") // JNA @Structure.FieldOrder maps by exact winnt.h field name — layout would break otherwise.
    open class IoCounters : Structure() {
        @JvmField var ReadOperationCount: Long = 0

        @JvmField var WriteOperationCount: Long = 0

        @JvmField var OtherOperationCount: Long = 0

        @JvmField var ReadTransferCount: Long = 0

        @JvmField var WriteTransferCount: Long = 0

        @JvmField var OtherTransferCount: Long = 0
    }

    /**
     * JOBOBJECT_EXTENDED_LIMIT_INFORMATION (winnt.h): a nested basic-limit struct,
     * an IO_COUNTERS block, then four SIZE_T memory-limit fields. The one we set is
     * [ProcessMemoryLimit] together with the `JOB_OBJECT_LIMIT_PROCESS_MEMORY` flag
     * in the nested basic limits. **Layout unverified on real Windows.**
     */
    @Structure.FieldOrder(
        "BasicLimitInformation",
        "IoInfo",
        "ProcessMemoryLimit",
        "JobMemoryLimit",
        "PeakProcessMemoryUsed",
        "PeakJobMemoryUsed",
    )
    @Suppress("PropertyName") // JNA @Structure.FieldOrder maps by exact winnt.h field name — layout would break otherwise.
    open class JobObjectExtendedLimitInformation : Structure() {
        @JvmField var BasicLimitInformation: JobObjectBasicLimitInformation = JobObjectBasicLimitInformation()

        @JvmField var IoInfo: IoCounters = IoCounters()

        @JvmField var ProcessMemoryLimit: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)

        @JvmField var JobMemoryLimit: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)

        @JvmField var PeakProcessMemoryUsed: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)

        @JvmField var PeakJobMemoryUsed: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    }
}
