package dev.kuml.cli.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import dev.kuml.ai.KumlAiException
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.cli.ExitCodes

// ─────────────────────────────────────────────────────────────────────────────
// `kuml ai vault` subcommand group — V3.1.20
//
// Sub-subcommands:
//   kuml ai vault unlock  — derive encryption key from master password and
//                           (re-)encrypt all existing plain entries in the vault
//   kuml ai vault lock    — zero-fill the in-memory key (no-op if not enabled)
//   kuml ai vault status  — show current vault backend and encryption state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `kuml ai vault` subcommand group.
 *
 * Provides CLI access to the master-password encryption layer introduced in V3.1.20.
 * Note: the CLI process is short-lived — "unlock" primarily means deriving the key,
 * verifying it against the per-vault sentinel, and (re-)encrypting stored plain entries.
 * Session-persistent unlock is a desktop concern; CLI unlock validates + re-encrypts.
 */
internal class AiVaultCommand : CliktCommand(name = "vault") {
    init {
        subcommands(AiVaultUnlockCommand(), AiVaultLockCommand(), AiVaultStatusCommand())
    }

    override fun help(context: Context): String = "Manage API key vault encryption (master password). V3.1.20."

    override fun run() = Unit
}

// ── `kuml ai vault unlock` ────────────────────────────────────────────────────

internal class AiVaultUnlockCommand : CliktCommand(name = "unlock") {
    override fun help(context: Context): String = "Derive encryption key from master password and re-encrypt all stored API keys."

    override fun run() {
        val vault = ApiKeyVault.detect()

        // Prompt for master password without echo.
        val password =
            System.console()?.readPassword("Master password: ")
                ?: run {
                    // Fallback for environments without a real console (CI, piped stdin).
                    System.err.println("Warning: no console available — password may be visible in process listing.")
                    print("Master password: ")
                    readlnOrNull()?.toCharArray()
                }
                ?: run {
                    echo("No password entered.", err = true)
                    throw ProgramResult(ExitCodes.USAGE)
                }

        if (password.isEmpty()) {
            echo("Master password must not be empty.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }

        try {
            // enableMasterPassword zero-fills the password CharArray in-place.
            vault.enableMasterPassword(password)
            echo("Vault unlocked — master-password encryption is active for this session.")
            echo("Backend: ${vault.backend.displayName}")
        } catch (e: KumlAiException.VaultUnavailable) {
            echo("Vault unavailable: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.ONLINE_ERROR)
        }
    }
}

// ── `kuml ai vault lock` ──────────────────────────────────────────────────────

internal class AiVaultLockCommand : CliktCommand(name = "lock") {
    override fun help(context: Context): String = "Zero-fill the in-memory encryption key. No-op if vault is not master-password protected."

    override fun run() {
        val vault = ApiKeyVault.detect()
        if (!vault.isMasterPasswordEnabled) {
            echo("Vault is not master-password protected — nothing to lock.")
            return
        }
        vault.lock()
        echo("Vault locked — in-memory encryption key has been zeroed.")
    }
}

// ── `kuml ai vault status` ────────────────────────────────────────────────────

internal class AiVaultStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context): String = "Show current vault backend and encryption state."

    override fun run() {
        val vault = ApiKeyVault.detect()
        val backend = vault.backend
        val encrypted = vault.isMasterPasswordEnabled
        val fallback = vault.isFallback

        echo("Vault backend  : ${backend.displayName}")
        echo("Encrypted (MPW): $encrypted")
        echo("Fallback (plain): $fallback")
    }
}
