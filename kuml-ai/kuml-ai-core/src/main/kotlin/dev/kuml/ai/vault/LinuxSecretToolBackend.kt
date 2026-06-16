package dev.kuml.ai.vault

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.internal.ShellOut

/**
 * Uses the `secret-tool` CLI (libsecret / GNOME Keyring) to store secrets on Linux.
 *
 * Commands used:
 *  - `secret-tool store --label=<label> service <service> key <key>`  (secret on stdin)
 *  - `secret-tool lookup service <service> key <key>`
 *  - `secret-tool clear service <service> key <key>`
 *
 * Requires `libsecret-tools` package (e.g., `apt install libsecret-tools`).
 * Falls back gracefully when the tool is not installed: isAvailable() returns false.
 */
public class LinuxSecretToolBackend(
    private val service: String = DEFAULT_SERVICE,
) : KeyVaultBackend {
    override val displayName: String get() = "libsecret (secret-tool)"

    override fun isAvailable(): Boolean =
        try {
            val result = ShellOut.run(listOf("secret-tool", "--version"))
            result.exitCode == 0
        } catch (_: Exception) {
            false
        }

    override fun put(
        key: String,
        secret: String,
    ) {
        val label = "$service/$key"
        // secret-tool reads the secret from stdin
        val result =
            ShellOut.run(
                command =
                    listOf(
                        "secret-tool",
                        "store",
                        "--label=$label",
                        "service",
                        service,
                        "key",
                        key,
                    ),
                stdin = secret,
            )
        if (result.exitCode != 0) {
            throw KumlAiException.VaultUnavailable(
                "secret-tool store failed for key '$key': ${result.stderr}",
            )
        }
    }

    override fun get(key: String): String? {
        val result =
            ShellOut.run(
                command =
                    listOf(
                        "secret-tool",
                        "lookup",
                        "service",
                        service,
                        "key",
                        key,
                    ),
            )
        return when {
            result.exitCode == 0 && result.stdout.isNotEmpty() -> result.stdout
            else -> null
        }
    }

    override fun delete(key: String) {
        ShellOut.run(
            command =
                listOf(
                    "secret-tool",
                    "clear",
                    "service",
                    service,
                    "key",
                    key,
                ),
        )
        // Ignore exit code — delete is idempotent
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "dev.kuml.ai"
    }
}
