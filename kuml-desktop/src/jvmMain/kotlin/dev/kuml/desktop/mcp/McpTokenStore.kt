package dev.kuml.desktop.mcp

import dev.kuml.desktop.io.AppPaths
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * Persists the bearer token of the local MCP server in its own file (never in `settings.json`,
 * which users may share). 256 bits from [SecureRandom], base64url without padding (43 chars —
 * no shell metacharacters, so the `claude mcp add …` command needs no quoting beyond the header).
 *
 * The file is created with owner-only permissions (POSIX 0600) from the start: the temp file is
 * created with the restrictive attribute and only then filled and atomically moved into place,
 * so there is never a window in which the token sits in a world-readable file. On filesystems
 * without POSIX permissions (Windows) this is best-effort.
 *
 * The token is never logged and never appears in a `toString()`.
 */
internal open class McpTokenStore(
    private val file: Path = AppPaths.settingsDir().resolve("mcp-token"),
) {
    private val random = SecureRandom()

    /** Returns the stored token, generating (and persisting) a fresh one if absent or invalid. */
    @Synchronized
    fun loadOrCreate(): String {
        val existing = readValid()
        if (existing != null) {
            tightenPermissions()
            return existing
        }
        return regenerate()
    }

    /** Replaces the stored token with a new random one and returns it. */
    @Synchronized
    open fun regenerate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        write(token)
        return token
    }

    private fun readValid(): String? =
        try {
            if (!Files.isRegularFile(file)) {
                null
            } else {
                Files.readString(file).trim().takeIf { TOKEN_PATTERN.matches(it) }
            }
        } catch (_: Exception) {
            null
        }

    private fun write(token: String) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(tmp)
        }
        Files.writeString(tmp, token)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun tightenPermissions() {
        try {
            val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java) ?: return
            if (view.readAttributes().permissions() != OWNER_ONLY) view.setPermissions(OWNER_ONLY)
        } catch (_: Exception) {
            // Best effort only -- never fail token loading because permissions could not be tightened.
        }
    }

    override fun toString(): String = "McpTokenStore(file=$file)"

    internal companion object {
        const val TOKEN_BYTES = 32
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
