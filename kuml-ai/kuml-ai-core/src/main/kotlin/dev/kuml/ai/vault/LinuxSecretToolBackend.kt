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
            val result = ShellOut.run(command = listOf("secret-tool", "--version"))
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
                message = "secret-tool store failed for key '$key': ${result.stderr}",
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

    /**
     * Tri-state existence probe — see [KeyVaultBackend.has]'s contract.
     *
     * Reuses `secret-tool lookup` (the same command as [get]) rather than `secret-tool search`.
     * `search`'s own exit code is 0 whenever the D-Bus query itself succeeded — REGARDLESS of
     * whether any item actually matched (verified against libsecret's
     * `secret_tool_action_search` in `tool/secret-tool.c`: it returns 0 whenever `error == NULL`,
     * match count notwithstanding) — so it cannot tell "not found" from "found" via exit code
     * alone. It also unconditionally loads and prints the matched secret's plaintext value to
     * stdout when a match exists (`SECRET_SEARCH_LOAD_SECRETS`), so it buys no confidentiality
     * benefit over `lookup` either.
     *
     * `lookup` itself (`secret_tool_action_lookup`) returns exit code 1 for BOTH the "not found"
     * case (`value == NULL`, no `GError` set, nothing written to stderr) AND any real backend
     * error (D-Bus/daemon unreachable, keyring locked — always `g_printerr`'d to stderr first,
     * before returning 1). The two are told apart here by stderr emptiness: a real error always
     * populates stderr before returning 1; "not found" never does.
     */
    override fun has(key: String): Boolean? {
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
        return interpretHasResult(exitCode = result.exitCode, stderrBlank = result.stderr.isBlank())
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "dev.kuml.ai"

        /**
         * Pure exit-code/stderr decision table for [has] — extracted so it is unit-testable
         * without shelling out to the real `secret-tool` CLI (there is no DI seam for [ShellOut]
         * in this module; see [LinuxSecretToolBackendTest]'s existing note on that limitation).
         */
        internal fun interpretHasResult(
            exitCode: Int,
            stderrBlank: Boolean,
        ): Boolean? =
            when {
                exitCode == 0 -> true
                exitCode == 1 && stderrBlank -> false // not found, no error reported
                else -> null // real backend error (stderr populated), or unexpected exit code
            }
    }
}
