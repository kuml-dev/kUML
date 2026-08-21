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
            val result = ShellOut.run(command = listOf("security", "list-keychains"))
            result.exitCode == 0
        } catch (_: Exception) {
            false
        }

    override fun put(
        key: String,
        secret: String,
    ) {
        // -U: update if already exists; -a $USER: account attribute; -s: service; -l: label; -w: password (via stdin)
        // The secret is intentionally NOT appended as a command-line argument — that would expose
        // it in the process table (visible via `ps aux`). Instead, `-w` without a trailing value
        // causes `security add-generic-password` to read the password from stdin, which we supply
        // via ShellOut's `stdin` parameter.
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
                    ),
                stdin = secret,
            )
        if (result.exitCode != 0) {
            throw KumlAiException.VaultUnavailable(
                message = "macOS Keychain put failed for key '$key': ${result.stderr}",
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

    /**
     * Tri-state existence probe — see [KeyVaultBackend.has]'s contract.
     *
     * Deliberately omits `-w`: `find-generic-password` without it only inspects the item's
     * metadata, never its secret value. That has two benefits over probing via [get]: (a) it
     * never reads the API key into the JVM heap just to answer "is one configured", and (b) it
     * does not require the "Always Allow" keychain-access consent that specifically guards the
     * *secret value* — so a locked keychain or an earlier-denied `-w` prompt does not make this
     * presence check itself pop a consent dialog.
     *
     * Exit codes are unambiguous here (Apple SecBase.h): `0` = found, `44`
     * (`errSecItemNotFound`) = definitely absent, anything else (auth failure, locked keychain,
     * `security` itself missing) is a genuine backend failure and MUST surface as `null` — never
     * collapse it into "absent" (that is exactly the fail-destructive bug this method exists to
     * fix; see [KeyVaultBackend.has]'s KDoc).
     */
    override fun has(key: String): Boolean? {
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
                    ),
            )
        return interpretHasExitCode(result.exitCode)
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

        /**
         * Pure exit-code decision table for [has] — extracted so it is unit-testable without
         * shelling out to the real `security` CLI (there is no DI seam for [ShellOut] in this
         * module; see [MacOsKeychainBackendTest]'s existing note on that limitation).
         */
        internal fun interpretHasExitCode(exitCode: Int): Boolean? =
            when (exitCode) {
                0 -> true
                44 -> false // errSecItemNotFound — definitely absent
                else -> null // auth failure / locked keychain / other backend error
            }
    }
}
