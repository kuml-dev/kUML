package dev.kuml.ai.vault

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.internal.ShellOut

/**
 * Uses the `security` CLI to interact with the macOS Keychain.
 *
 * Commands used:
 *  - `security add-generic-password` (with -U to update existing)
 *  - `security find-generic-password`
 *  - `security delete-generic-password`
 *
 * The secret is passed via stdin for `add-generic-password` (-w flag)
 * to avoid appearing in the process argument list.
 *
 * Note: The first keychain access shows a system dialog
 * "kuml wants to access 'dev.kuml.ai' in your keychain". This is
 * expected macOS behaviour. Users should click "Always Allow".
 */
public class MacOsKeychainBackend(
    /** Service name used as a logical group in the macOS Keychain. */
    private val service: String = DEFAULT_SERVICE,
) : KeyVaultBackend {
    override val displayName: String get() = "macOS Keychain"

    override fun isAvailable(): Boolean =
        try {
            val result = ShellOut.run(listOf("security", "list-keychains"))
            result.exitCode == 0
        } catch (_: Exception) {
            false
        }

    override fun put(
        key: String,
        secret: String,
    ) {
        // -U: update if already exists; -a $USER: account attribute; -s: service; -l: label; -w: password (via stdin)
        val user = System.getProperty("user.name") ?: "kuml"
        val result =
            ShellOut.run(
                command =
                    listOf(
                        "security",
                        "add-generic-password",
                        "-U",
                        "-a",
                        user,
                        "-s",
                        service,
                        "-l",
                        key,
                        "-w",
                        secret,
                    ),
            )
        if (result.exitCode != 0) {
            throw KumlAiException.VaultUnavailable(
                "macOS Keychain put failed for key '$key': ${result.stderr}",
            )
        }
    }

    override fun get(key: String): String? {
        val user = System.getProperty("user.name") ?: "kuml"
        val result =
            ShellOut.run(
                command =
                    listOf(
                        "security",
                        "find-generic-password",
                        "-a",
                        user,
                        "-s",
                        service,
                        "-l",
                        key,
                        "-w",
                    ),
            )
        return when (result.exitCode) {
            0 -> result.stdout.trim()
            44 -> null // errSecItemNotFound
            else -> null // other failure — treat as not found
        }
    }

    override fun delete(key: String) {
        val user = System.getProperty("user.name") ?: "kuml"
        ShellOut.run(
            command =
                listOf(
                    "security",
                    "delete-generic-password",
                    "-a",
                    user,
                    "-s",
                    service,
                    "-l",
                    key,
                ),
        )
        // Ignore exit code — delete is idempotent (no-op if not found)
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "dev.kuml.ai"
    }
}
