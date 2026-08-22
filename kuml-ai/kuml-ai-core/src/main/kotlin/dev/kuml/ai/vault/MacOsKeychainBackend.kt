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
 * "kuml wants to access '...' in your keychain". This is expected macOS
 * behaviour. Users should click "Always Allow".
 *
 * V3.7.4 -- CREDENTIAL-MISROUTING FIX: the identity of a `kSecClassGenericPassword` item is
 * the pair (account, service) -- NOT the `-l`/label attribute. Before this version every
 * provider's key was stored under the *same* (account, [DEFAULT_SERVICE]) pair, differing
 * only by label; `-l` looks like a filter to `find-generic-password` but is NOT evaluated as
 * one (verified empirically 2026-08-22 against a throwaway service: `find-generic-password
 * -a $USER -s dev.kuml.ai -l "OpenAILLMProvider" -w` returned exit 0 and the *Anthropic*
 * secret). Every `put()` therefore silently overwrote the previous provider's item, and every
 * `get()`/`has()` read whichever single item happened to be stored -- an API key for one
 * provider could be sent to a completely different provider's endpoint. Fixed by folding the
 * key into the *service* string via [serviceFor], so each provider gets its own
 * (account, service) identity.
 */
public class MacOsKeychainBackend(
    /** Base service name -- [serviceFor] derives the actual per-key service from this. */
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
        requireSafeKey(key)
        requireSafeSecret(secret)
        // -U: update if already exists; -a $USER: account attribute; -s: service (key-scoped,
        // see class KDoc); -l: label (cosmetic only, kept for Keychain Access readability);
        // -w: password (via stdin).
        // The secret is intentionally NOT appended as a command-line argument -- that would
        // expose it in the process table (visible via `ps aux`). Instead, `-w` without a
        // trailing value makes `security add-generic-password` read the password from stdin.
        //
        // TWO lines, not one -- verified empirically 2026-08-22 (live-Keychain contract run):
        // when stdin is a pipe (not a real TTY), `security` prompts "password data for new
        // item:" AND THEN "retype password for new item:" and reads ONE stdin line for each,
        // exactly like its interactive confirm-password prompt. Writing the secret only once
        // (as originally implemented) satisfies the first prompt and starves the second, which
        // then reads EOF/empty -- `security` still exits 0, but silently stores an EMPTY
        // secret. This applies to a brand-new item AND to updating an existing one (`-U`) --
        // both prompts fire every time. get() would then return "" instead of throwing, so this
        // failure mode is silent unless specifically tested against a real Keychain, which is
        // exactly why the live contract test (gated behind kuml.ai.vault.liveKeystoreTests) is
        // mandatory acceptance for this welle, not optional.
        val result =
            ShellOut.run(
                command = putCommand(user = accountName, baseService = service, key = key),
                stdin = "$secret\n$secret\n",
            )
        if (result.exitCode != 0) {
            throw KumlAiException.VaultUnavailable(
                message = "macOS Keychain put failed for key '$key': ${result.stderr}",
            )
        }
        // Read-back verification -- the two-prompt stdin race documented above means `security`
        // can exit 0 while having stored an EMPTY or truncated secret (desynced prompts). The
        // exit code alone cannot detect this; only comparing what actually landed against what
        // we intended to write can. This mirrors the review finding that motivated it: a silent
        // storage corruption must surface here as a hard failure, not as a later, opaque 401 at
        // the LLM provider.
        val stored = get(key)
        if (stored != secret) {
            throw KumlAiException.VaultUnavailable(
                message =
                    "macOS Keychain put for key '$key' reported success (exit 0) but the " +
                        "stored secret does not match what was written -- read-back verification " +
                        "failed. This can happen if the interactive add-generic-password " +
                        "prompt sequence desynced; retry the save.",
            )
        }
    }

    override fun get(key: String): String? {
        requireSafeKey(key)
        val result =
            ShellOut.run(
                command = findCommand(user = accountName, baseService = service, key = key, withSecret = true),
            )
        return when (result.exitCode) {
            // An empty stdout on exit 0 means the Keychain item exists but its secret is empty --
            // treat that identically to "absent" so callers (KumlAiExecutor's Elvis-operator
            // MissingApiKey check, has()'s default `get(key) != null`) never mistake a corrupted,
            // empty-valued item for a configured API key. See the class KDoc's two-prompt-desync
            // failure mode this guards against.
            0 -> result.stdout.trim().takeIf { it.isNotEmpty() }
            44 -> null // errSecItemNotFound
            else -> null // other failure -- treat as not found
        }
    }

    /**
     * Tri-state existence probe -- see [KeyVaultBackend.has]'s contract.
     *
     * Deliberately omits `-w`: `find-generic-password` without it only inspects the item's
     * metadata, never its secret value. That has two benefits over probing via [get]: (a) it
     * never reads the API key into the JVM heap just to answer "is one configured", and (b) it
     * does not require the "Always Allow" keychain-access consent that specifically guards the
     * *secret value* -- so a locked keychain or an earlier-denied `-w` prompt does not make this
     * presence check itself pop a consent dialog.
     *
     * Exit codes are unambiguous here (Apple SecBase.h): `0` = found, `44`
     * (`errSecItemNotFound`) = definitely absent, anything else (auth failure, locked keychain,
     * `security` itself missing) is a genuine backend failure and MUST surface as `null` --
     * never collapse it into "absent" (that is exactly the fail-destructive bug this method
     * exists to fix; see [KeyVaultBackend.has]'s KDoc).
     */
    override fun has(key: String): Boolean? {
        requireSafeKey(key)
        val result =
            ShellOut.run(
                command = findCommand(user = accountName, baseService = service, key = key, withSecret = false),
            )
        return interpretHasExitCode(result.exitCode)
    }

    override fun delete(key: String) {
        requireSafeKey(key)
        ShellOut.run(
            command = deleteCommand(user = accountName, baseService = service, key = key),
        )
        // Ignore exit code -- delete is idempotent (no-op if not found)
    }

    /**
     * True, if on this machine there is still an item under the OLD, shared service ([service]
     * without a key suffix) -- leftover from before V3.7.4, when every provider overwrote the
     * same item.
     *
     * This item is NEVER read (its label does not reliably name its provider -- that is exactly
     * the field the bug overwrote) and NEVER deleted (it is a human's key). The return value is
     * used solely to surface a hint in the provider settings dialog.
     */
    public fun legacySharedItemExists(): Boolean {
        val result =
            ShellOut.run(
                command = legacyProbeCommand(user = accountName, baseService = service),
            )
        return interpretHasExitCode(result.exitCode) == true
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "dev.kuml.ai"

        /** Shared account-name resolution -- identical across all call sites. */
        internal val accountName: String get() = System.getProperty("user.name") ?: "kuml"

        /**
         * IDENTITY of a `kSecClassGenericPassword` item is (Account, Service). The label (`-l`)
         * is purely cosmetic for the Keychain Access UI and is NOT evaluated by
         * `find-generic-password` as a search predicate -- verified 2026-08-22 against a
         * throwaway service. Because of that, the key MUST go into the service. Whoever finds
         * this suffix redundant and removes it reintroduces the V3.7.3 credential-misrouting
         * bug.
         */
        internal fun serviceFor(
            baseService: String,
            key: String,
        ): String = "$baseService.$key"

        internal fun putCommand(
            user: String,
            baseService: String,
            key: String,
        ): List<String> =
            listOf(
                "security",
                "add-generic-password",
                "-U",
                "-a",
                user,
                "-s",
                serviceFor(baseService = baseService, key = key),
                "-l",
                key,
                "-w",
            )

        internal fun findCommand(
            user: String,
            baseService: String,
            key: String,
            withSecret: Boolean,
        ): List<String> {
            val base =
                mutableListOf(
                    "security",
                    "find-generic-password",
                    "-a",
                    user,
                    "-s",
                    serviceFor(baseService = baseService, key = key),
                    "-l",
                    key,
                )
            if (withSecret) base += "-w"
            return base
        }

        internal fun deleteCommand(
            user: String,
            baseService: String,
            key: String,
        ): List<String> =
            listOf(
                "security",
                "delete-generic-password",
                "-a",
                user,
                "-s",
                serviceFor(baseService = baseService, key = key),
                "-l",
                key,
            )

        /**
         * Existence probe against the OLD, shared (pre-V3.7.4) service -- deliberately WITHOUT
         * `-l` (there is no single reliable label to match -- that is the field the bug
         * overwrote) and WITHOUT `-w` (an existence check must never pull an unknown-owner
         * secret into the JVM heap, and must never trigger a consent dialog).
         */
        internal fun legacyProbeCommand(
            user: String,
            baseService: String,
        ): List<String> =
            listOf(
                "security",
                "find-generic-password",
                "-a",
                user,
                "-s",
                baseService,
            )

        /**
         * Pure exit-code decision table for [has] -- extracted so it is unit-testable without
         * shelling out to the real `security` CLI (there is no DI seam for [ShellOut] in this
         * module; see [MacOsKeychainBackendTest]'s existing note on that limitation).
         */
        internal fun interpretHasExitCode(exitCode: Int): Boolean? =
            when (exitCode) {
                0 -> true
                44 -> false // errSecItemNotFound -- definitely absent
                else -> null // auth failure / locked keychain / other backend error
            }

        /**
         * Defensive guard: a blank key would build a nonsensical service string, and a NUL byte
         * in [key] would silently break the `security` CLI's argv parsing (ProcessBuilder
         * itself is NUL-safe -- no shell is involved -- but the external `security` binary is
         * not guaranteed to be). Checked via [Char.code] rather than a NUL char literal, which
         * some source-file tooling mangles.
         */
        internal fun requireSafeKey(key: String) {
            if (key.isBlank() || key.any { it.code == 0 }) {
                throw KumlAiException.VaultUnavailable(
                    message = "Invalid vault key (blank or contains a NUL byte)",
                )
            }
        }

        /**
         * Defensive guard for [put]'s secret argument. Rejects two shapes that the two-prompt
         * stdin race (see class KDoc) turns into a SILENT storage corruption rather than a clean
         * failure:
         *  - a blank secret would store nothing meaningful and should never be written at all;
         *  - a secret containing `\n`/`\r` desyncs the "password data for new item:" /
         *    "retype password for new item:" prompt pair -- the first prompt consumes up to the
         *    embedded newline, the second consumes the remainder, and what is actually stored is
         *    silently truncated to the text before the first embedded newline. A NUL byte is
         *    rejected for the same argv-safety reason as in [requireSafeKey].
         *
         * This is defense-in-depth: [dev.kuml.desktop.ai.settings.AiProviderSettingsState.saveApiKey]
         * already trims and length-caps its input, but `trim()` only strips leading/trailing
         * whitespace -- an *internal* newline (plausible after copy-pasting a key from
         * line-wrapped mail/terminal output) survives that trim and must be caught here instead.
         */
        internal fun requireSafeSecret(secret: String) {
            if (secret.isBlank() || secret.any { it == '\n' || it == '\r' || it.code == 0 }) {
                throw KumlAiException.VaultUnavailable(
                    message = "Invalid vault secret (blank or contains a newline/NUL byte)",
                )
            }
        }
    }
}
