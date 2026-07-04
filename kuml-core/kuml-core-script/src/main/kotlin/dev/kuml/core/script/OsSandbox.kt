package dev.kuml.core.script

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * OS-native isolation for script-worker child processes (Welle 4 of the
 * MCP-Sandbox architecture).
 *
 * Wellen 2-3 put every untrusted script in a **separate JVM** with a wall-clock
 * timeout, a heap cap, a minimal environment and a fixed argv — that contains
 * hangs, OOM and command injection. But a child JVM without an OS cage still
 * runs with the **full privileges of the desktop user**: if the regex denylist
 * (layer 1) is bypassed, the script can still read `~/.ssh`, open a socket to
 * exfiltrate it, or drop a launchd persistence plist. This layer closes that
 * gap by launching the worker inside an OS-enforced sandbox.
 *
 * ## Current platform coverage
 *
 *  - **macOS** — `sandbox-exec` with a strict `.sb` profile
 *    ([SANDBOX_PROFILE_RESOURCE]): deny-by-default, `network*` denied, writes
 *    confined to a per-worker temp dir, reads on the top secret stores denied.
 *    Empirically verified to block file-write to `$HOME`, DNS- and raw-IP
 *    network connects, and reads of `~/.ssh`, while letting legitimate DSL
 *    scripts (which need the embedded Kotlin compiler + temp writes) run
 *    unhindered.
 *  - **Linux / Windows** — not yet (separate later waves). On those platforms
 *    [wrap] returns the command unchanged and [isolationAvailable] is false, so
 *    the fail-closed policy in [WorkerProcessSupport] decides whether that is
 *    tolerated (see `KUML_MCP_SANDBOX_OS_ISOLATION`).
 *
 * ## `sandbox-exec` ⟂ AMFI (verified)
 *
 * `sandbox-exec` is officially deprecated but present on every macOS and heavily
 * used by Apple itself. Critically it is **orthogonal** to AMFI / Library
 * Validation: `sandbox-exec` carries no hardened-runtime codesign flag and does
 * not impose Library Validation on its child, so wrapping the ad-hoc-signed
 * bundled jlink JRE in it does **not** trigger the separate AMFI adhoc-dylib
 * rejection documented for launches from a hardened parent. The two mechanisms
 * do not interact. (See the "MCP-Server AMFI-Signierungsproblem" note.)
 *
 * V0.23.3 — Welle 4.
 */
internal object OsSandbox {
    /** Classpath resource holding the macOS seatbelt profile. */
    const val SANDBOX_PROFILE_RESOURCE: String = "/dev/kuml/core/script/sandbox/kuml-worker.macos.sb"

    /** `sandbox-exec` lives here on every macOS; a fixed absolute path (no PATH lookup). */
    const val SANDBOX_EXEC_PATH: String = "/usr/bin/sandbox-exec"

    /**
     * Isolation strictness for the current host, from
     * `KUML_MCP_SANDBOX_OS_ISOLATION`:
     *
     *  - `required` — a worker MUST be OS-isolated. If OS isolation is
     *    unavailable (non-macOS today, or `sandbox-exec` missing / profile not
     *    extractable), worker launch **fails closed**: no ungated child is ever
     *    started. This is the default **on macOS**.
     *  - `best-effort` — use OS isolation when available, otherwise fall back to
     *    the plain (Wellen 2-3) child process. This is the default on platforms
     *    where OS isolation is not yet implemented (Linux/Windows), so those
     *    platforms keep working with process/heap/timeout containment until
     *    their own OS-isolation waves land. An operator can force `required`
     *    there too, which will fail closed until then.
     */
    enum class Mode { REQUIRED, BEST_EFFORT }

    const val ENV_OS_ISOLATION: String = "KUML_MCP_SANDBOX_OS_ISOLATION"
    const val MODE_REQUIRED: String = "required"
    const val MODE_BEST_EFFORT: String = "best-effort"

    private val isMac: Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .let { it.contains("mac") || it.contains("darwin") }

    /** Resolved isolation mode from the environment, with a platform-sensitive default. */
    fun mode(): Mode = modeFrom(System.getenv(ENV_OS_ISOLATION), isMac)

    /** Testable resolver: `required` default on macOS, `best-effort` elsewhere. */
    fun modeFrom(
        raw: String?,
        onMac: Boolean,
    ): Mode =
        when (raw?.trim()?.lowercase()) {
            MODE_REQUIRED -> Mode.REQUIRED
            MODE_BEST_EFFORT -> Mode.BEST_EFFORT
            else -> if (onMac) Mode.REQUIRED else Mode.BEST_EFFORT
        }

    /**
     * True if this host can actually enforce OS isolation right now: macOS with
     * an executable `sandbox-exec` and an extractable profile.
     */
    fun isolationAvailable(): Boolean = isMac && File(SANDBOX_EXEC_PATH).canExecute() && profileFileOrNull() != null

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
     */
    fun wrap(
        command: List<String>,
        workDir: File,
    ): List<String> {
        val mode = mode()
        if (!isMac) {
            // Other platforms: OS isolation is a later wave. Fail closed only if
            // the operator explicitly demanded `required`.
            if (mode == Mode.REQUIRED) {
                throw SandboxUnavailableException(
                    "OS-level sandbox isolation is required but not implemented on this platform " +
                        "(${System.getProperty("os.name")}). Set $ENV_OS_ISOLATION=$MODE_BEST_EFFORT to allow " +
                        "process/heap/timeout containment without an OS cage, or run on macOS.",
                )
            }
            return command
        }

        val sandboxExec = File(SANDBOX_EXEC_PATH)
        val profile = profileFileOrNull()
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
        // Canonicalize the workdir: the seatbelt kernel matches the real path
        // (macOS temp is /var/folders/… → /private/var/folders/…), so the
        // `subpath` param must be canonical or legitimate writes get denied.
        val canonicalWorkDir = runCatching { workDir.canonicalPath }.getOrDefault(workDir.absolutePath)
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

    private fun userHome(): String = System.getProperty("user.home") ?: "/var/empty"

    /**
     * Extracts the bundled `.sb` profile to a stable per-JVM temp file (once)
     * and returns it, or null if extraction fails. The file is created with
     * owner-only permissions and marked delete-on-exit.
     */
    @Volatile private var cachedProfile: File? = null

    @Synchronized
    private fun profileFileOrNull(): File? {
        cachedProfile?.let { if (it.isFile) return it }
        return try {
            val bytes =
                OsSandbox::class.java.getResourceAsStream(SANDBOX_PROFILE_RESOURCE)?.use { it.readBytes() }
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
