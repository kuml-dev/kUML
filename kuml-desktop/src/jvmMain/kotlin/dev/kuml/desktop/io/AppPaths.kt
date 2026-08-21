package dev.kuml.desktop.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

object AppPaths {
    fun settingsDir(): Path = resolveBaseDir().also { Files.createDirectories(it) }

    fun settingsFile(): Path = settingsDir().resolve("desktop-settings.json")

    /**
     * Directory for rolled application log files. Created on first access, in the
     * same per-user base directory as [settingsDir] (where `secrets.json` also
     * lives). Best-effort restricted to owner-only (POSIX 0700), mirroring the
     * 0600 restriction PlainJsonFallbackBackend applies to `secrets.json` —
     * no-op on filesystems without POSIX permissions (e.g. Windows).
     *
     * [baseDir] defaults to [resolveBaseDir] but is injectable — analogous to how
     * [resolveBaseDir] itself takes injectable `os`/`env`/`userHome` parameters —
     * so tests can point this at a temp directory instead of writing into the
     * real per-user application-support directory.
     */
    fun logDir(baseDir: Path = resolveBaseDir()): Path =
        baseDir.resolve("logs").also {
            Files.createDirectories(it)
            restrictToOwnerOnly(it)
        }

    /**
     * Restricts [path] to owner read/write/execute only (POSIX 0700). Silently
     * no-ops on non-POSIX filesystems (Windows) or if permissions cannot be
     * applied — best-effort hardening only, never fails the caller.
     */
    private fun restrictToOwnerOnly(path: Path) {
        try {
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java) ?: return
            view.setPermissions(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: Exception) {
            // Best-effort hardening only — never fail directory creation because perms
            // could not be tightened.
        }
    }

    internal fun resolveBaseDir(
        os: String = System.getProperty("os.name", "").lowercase(),
        env: Map<String, String?> = System.getenv(),
        userHome: String = System.getProperty("user.home", ""),
    ): Path =
        when {
            "mac" in os -> Paths.get(userHome, "Library", "Application Support", "kUML")
            "win" in os -> Paths.get(env["APPDATA"] ?: Paths.get(userHome, "AppData", "Roaming").toString(), "kUML")
            else -> Paths.get(env["XDG_CONFIG_HOME"] ?: Paths.get(userHome, ".config").toString(), "kuml")
        }
}
