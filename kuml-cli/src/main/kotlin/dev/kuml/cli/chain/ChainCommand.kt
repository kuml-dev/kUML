package dev.kuml.cli.chain

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.ModelSignature
import dev.kuml.runtime.chain.evm.Eip712Verifier
import dev.kuml.runtime.chain.evm.EvmChainAdapter
import dev.kuml.runtime.chain.evm.EvmChainAdapterException
import dev.kuml.runtime.chain.evm.ModelSigner
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** The `kuml chain` subcommand group (V3.0.5). */
internal class ChainCommand(
    adapterFactory: () -> KumlChainAdapter = { EvmChainAdapter() },
) : CliktCommand(name = "chain") {
    init {
        subcommands(
            ChainConnectCommand(adapterFactory),
            ChainVerifyCommand(adapterFactory),
            ChainEventsCommand(adapterFactory),
            ChainSignCommand(),
            ChainVerifySigCommand(),
        )
    }

    override fun help(context: Context): String =
        "Inspect an on-chain registered kUML model (connect, verify hash, list events, sign, verify-sig)."

    override fun run() = Unit
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

// ── kuml chain connect ───────────────────────────────────────────────────────

internal class ChainConnectCommand(
    private val adapterFactory: () -> KumlChainAdapter,
) : CliktCommand(name = "connect") {
    private val rpc by option("--rpc", help = "JSON-RPC endpoint URL").required()
    private val contract by option("--contract", help = "Contract address (0x + 40 hex)").required()

    override fun help(context: Context): String = "Connect to an EVM contract and print its on-chain kUML model identity."

    override fun run() {
        val identity = connectOrExit(rpc, contract)
        echo("Connected to contract ${identity.address}")
        echo("  modelUri:      ${identity.modelUri}")
        echo("  schemaVersion: ${identity.schemaVersion}")
        echo("  modelHash:     ${hex(identity.modelHash)}")
    }

    private fun connectOrExit(
        rpc: String,
        contract: String,
    ): ContractIdentity = chainConnectOrExit(adapterFactory(), rpc, contract, this)
}

// ── kuml chain verify ────────────────────────────────────────────────────────

internal class ChainVerifyCommand(
    private val adapterFactory: () -> KumlChainAdapter,
) : CliktCommand(name = "verify") {
    private val rpc by option("--rpc", help = "JSON-RPC endpoint URL").required()
    private val contract by option("--contract", help = "Contract address (0x + 40 hex)").required()
    private val model by argument(help = "Local *.kuml.kts model file to hash and compare")

    override fun help(context: Context): String =
        "Compare a local model's canonical hash against the on-chain modelHash. " +
            "Exit ${ExitCodes.CHAIN_HASH_MISMATCH} on mismatch."

    override fun run() {
        val source =
            try {
                File(model).readText()
            } catch (e: IOException) {
                echo("I/O error reading '$model': ${e.message}", err = true)
                throw ProgramResult(ExitCodes.IO_ERROR)
            }
        val localHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(source))

        val identity = chainConnectOrExit(adapterFactory(), rpc, contract, this)
        val onChainHash = identity.modelHash

        echo("Local hash:    ${hex(localHash)}")
        echo("On-chain hash: ${hex(onChainHash)}")

        if (localHash.contentEquals(onChainHash)) {
            echo("MATCH — local model matches on-chain registration.")
        } else {
            echo("MISMATCH — local model does not match the on-chain modelHash.", err = true)
            throw ProgramResult(ExitCodes.CHAIN_HASH_MISMATCH)
        }
    }
}

// ── kuml chain events ────────────────────────────────────────────────────────

internal class ChainEventsCommand(
    private val adapterFactory: () -> KumlChainAdapter,
) : CliktCommand(name = "events") {
    private val rpc by option("--rpc", help = "JSON-RPC endpoint URL").required()
    private val contract by option("--contract", help = "Contract address (0x + 40 hex)").required()
    private val fromBlock by option("--from-block", help = "First block to replay (inclusive, default 0)")
        .long()
        .default(0L)
    private val limit by option("--limit", help = "Max events to print (default 100)")
        .int()
        .default(100)

    override fun help(context: Context): String = "List historical on-chain events via replay(fromBlock)."

    override fun run() {
        if (fromBlock < 0) {
            echo("--from-block must be >= 0, was $fromBlock", err = true)
            throw ProgramResult(ExitCodes.CHAIN_CONNECT_ERROR)
        }
        if (limit < 0) {
            echo("--limit must be >= 0, was $limit", err = true)
            throw ProgramResult(ExitCodes.CHAIN_CONNECT_ERROR)
        }

        // The adapter instance must be shared: connect() sets internal client/address state
        // that replay() requires — creating a fresh instance from the factory would lose
        // that state between the two calls.
        val adapter = adapterFactory()
        chainConnectOrExit(adapter, rpc, contract, this)

        val events: List<ChainEvent> =
            try {
                runBlocking {
                    if (limit == 0) emptyList() else adapter.replay(fromBlock).take(limit).toList()
                }
            } catch (e: EvmChainAdapterException) {
                echo("Chain error while reading events: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.CHAIN_CONNECT_ERROR)
            }

        echo("Events from block $fromBlock (showing up to $limit):")
        if (events.isEmpty()) {
            echo("  (no events)")
            return
        }
        for (e in events) {
            echo("  block ${e.blockNumber}  tx ${e.txHash}  ${e.eventType}  payloadAbi=${e.payloadAbi.size}B")
        }
        echo("Total: ${events.size} event(s).")
    }
}

// ── kuml chain sign ──────────────────────────────────────────────────────────

/**
 * `kuml chain sign <model> --private-key <hex>` — erzeugt eine EIP-712-Signatur
 * über den kanonischen Modell-Hash und schreibt sie als JSON in `<model>.sig`.
 *
 * SECURITY: Der Private Key sollte nie direkt als Shell-Argument übergeben werden
 * (erscheint dann in der Prozessliste). Empfohlen: via Environment-Variable +
 * Shell-Command-Substitution, z.B. `--private-key "$KUML_SIGNING_KEY"`.
 */
internal class ChainSignCommand : CliktCommand(name = "sign") {
    private val model by argument(help = "Local *.kuml.kts model file to sign")
    private val privateKey by option(
        "--private-key",
        help =
            "secp256k1 private key (64-char hex, optional 0x prefix). " +
                "SECURITY: prefer passing via shell command substitution from an env var, e.g. " +
                "--private-key \"\$KUML_SIGNING_KEY\", so the key never appears in shell history.",
    ).required()
    private val out by option("--out", help = "Output path for the .sig file (default: <model>.sig)")

    override fun help(context: Context): String =
        "Sign a local kUML model with a secp256k1 private key (EIP-712). " +
            "Writes a JSON signature file. Exit ${ExitCodes.CHAIN_INVALID_SIGNATURE} on key error."

    override fun run() {
        val source = readFileOrIoExit(model, this)
        val sig =
            try {
                ModelSigner().sign(source, privateKey)
            } catch (e: IllegalArgumentException) {
                echo("Invalid private key or signing failure: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.CHAIN_INVALID_SIGNATURE)
            }
        val json = sig.toJson()
        val target = out ?: "$model.sig"
        try {
            File(target).writeText(json)
        } catch (e: IOException) {
            echo("I/O error writing '$target': ${e.message}", err = true)
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
        echo("Signed by ${sig.signer}")
        echo("Signature written to $target")
        echo(json)
    }
}

// ── kuml chain verify-sig ─────────────────────────────────────────────────────

/**
 * `kuml chain verify-sig <model> [--sig <file>] [--expected-signer <addr>]` —
 * prüft eine EIP-712-Signatur gegen den lokalen Modell-Quelltext.
 */
internal class ChainVerifySigCommand : CliktCommand(name = "verify-sig") {
    private val model by argument(help = "Local *.kuml.kts model file the signature covers")
    private val sigFile by option(
        "--sig",
        help = "Path to the .sig JSON file (default: <model>.sig)",
    )
    private val expectedSigner by option(
        "--expected-signer",
        help = "Require the recovered address to equal this (EIP-55 or lowercase, case-insensitive)",
    )

    override fun help(context: Context): String =
        "Verify a kUML model EIP-712 signature (.sig file). " +
            "Exit ${ExitCodes.CHAIN_INVALID_SIGNATURE} if invalid; " +
            "${ExitCodes.CHAIN_SIGNER_MISMATCH} if --expected-signer does not match."

    override fun run() {
        val source = readFileOrIoExit(model, this)
        val sigPath = sigFile ?: "$model.sig"
        val sigJson = readFileOrIoExit(sigPath, this)
        val sig =
            try {
                ModelSignature.fromJson(sigJson)
            } catch (e: Exception) {
                echo("Malformed signature file '$sigPath': ${e.message}", err = true)
                throw ProgramResult(ExitCodes.CHAIN_INVALID_SIGNATURE)
            }

        val valid = Eip712Verifier().verifyModelSignature(source, sig)
        if (!valid) {
            echo("INVALID — signature does not verify against this model.", err = true)
            throw ProgramResult(ExitCodes.CHAIN_INVALID_SIGNATURE)
        }
        echo("VALID — signed by ${sig.signer}")

        val expected = expectedSigner
        if (expected != null) {
            val recovered = ModelSigner().recover(source, sig) // == sig.signer (already verified)
            if (!recovered.equals(expected, ignoreCase = true)) {
                echo(
                    "SIGNER MISMATCH — expected $expected but signature is from $recovered",
                    err = true,
                )
                throw ProgramResult(ExitCodes.CHAIN_SIGNER_MISMATCH)
            }
            echo("Signer matches expected address.")
        }
    }
}

// ── shared connect helper ────────────────────────────────────────────────────

/**
 * Connects [adapter] to the given [rpc]/[contract], routing error messages
 * through Clikt's [cmd] echo so that the test harness captures them in
 * `TestResult.stderr` (Clikt's `.test()` does not capture raw `System.err`).
 */
private fun chainConnectOrExit(
    adapter: KumlChainAdapter,
    rpc: String,
    contract: String,
    cmd: CliktCommand,
): ContractIdentity =
    try {
        runBlocking { adapter.connect(rpc, contract) }
    } catch (e: IllegalArgumentException) {
        // blank URL / invalid address format / SSRF-blocked URL
        cmd.echo("Invalid chain connection parameters: ${e.message}", err = true)
        throw ProgramResult(ExitCodes.CHAIN_CONNECT_ERROR)
    } catch (e: EvmChainAdapterException) {
        cmd.echo("Could not connect to chain: ${e.message}", err = true)
        throw ProgramResult(ExitCodes.CHAIN_CONNECT_ERROR)
    }

// ── shared file-read helper ───────────────────────────────────────────────────

/** Reads a file, or exits with [ExitCodes.IO_ERROR] if reading fails. */
private fun readFileOrIoExit(
    path: String,
    cmd: CliktCommand,
): String =
    try {
        File(path).readText()
    } catch (e: IOException) {
        cmd.echo("I/O error reading '$path': ${e.message}", err = true)
        throw ProgramResult(ExitCodes.IO_ERROR)
    }
