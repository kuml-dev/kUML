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
import dev.kuml.runtime.chain.evm.EvmChainAdapter
import dev.kuml.runtime.chain.evm.EvmChainAdapterException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** The `kuml chain` subcommand group (V3.0.4). */
internal class ChainCommand(
    adapterFactory: () -> KumlChainAdapter = { EvmChainAdapter() },
) : CliktCommand(name = "chain") {
    init {
        subcommands(
            ChainConnectCommand(adapterFactory),
            ChainVerifyCommand(adapterFactory),
            ChainEventsCommand(adapterFactory),
        )
    }

    override fun help(context: Context): String = "Inspect an on-chain registered kUML model (connect, verify hash, list events)."

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
