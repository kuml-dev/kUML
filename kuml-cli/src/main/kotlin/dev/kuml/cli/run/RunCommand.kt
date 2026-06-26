package dev.kuml.cli.run

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.snapshot.MigrationPolicy

/**
 * The `run` subcommand — starts a kUML state machine or activity with a
 * live event adapter.
 *
 * ## Adapters
 *  - `stdin` (default): interactive event loop reading from STDIN
 *  - `mcp`: starts a lightweight HTTP server with 5 REST endpoints
 *  - `batch`: loads events from `--events <file.json>` and runs to completion
 *  - `chain-evm`: subscribes to on-chain events from an EVM-compatible contract
 *
 * ## Snapshot support
 *  - `--restore <snapshot.json>`: restore session from a previous snapshot
 *  - `--snapshot-out <path>`: write snapshot when session ends
 *  - `--migration`: policy for snapshot compatibility (reject|fingerprint|vertices)
 *
 * ## chain-evm options
 *  - `--rpc <url>`: EVM JSON-RPC endpoint (http/https only, no private ranges)
 *  - `--contract <address>`: contract address (40 hex chars, optional 0x prefix)
 *  - `--from-block <long>`: replay from block number (omit for live subscribe)
 *  - `--chain-id <int>`: chain ID hint (informational only; EvmChainAdapter always auto-detects from RPC and ignores this value)
 */
internal class RunCommand : CliktCommand(name = "run") {
    private val script by argument(help = "Path to *.kuml.kts state-machine or activity script")
        .file(mustExist = true, canBeDir = false)

    private val adapter by option(
        "--adapter",
        help = "Event adapter: stdin (interactive), mcp (HTTP server), batch (file-driven), chain-evm (on-chain events)",
    ).choice("stdin", "mcp", "batch", "chain-evm").default("stdin")

    private val rpc by option(
        "--rpc",
        help = "EVM JSON-RPC endpoint (required for --adapter chain-evm, http/https only)",
    )

    private val contract by option(
        "--contract",
        help = "On-chain contract address (required for --adapter chain-evm, 40 hex chars, optional 0x prefix)",
    )

    private val fromBlock by option(
        "--from-block",
        help = "Replay from this block number (chain-evm only; omit for live subscribe)",
    ).long()

    private val chainId by option(
        "--chain-id",
        help = "EVM chain ID hint (chain-evm only; informational — adapter always auto-detects from RPC, this value is not forwarded)",
    ).int()

    private val port by option(
        "--port",
        help = "TCP port for --adapter mcp (0 = random free port)",
    ).int().default(0)

    private val events by option(
        "--events",
        help = "Path to events JSON file (required for --adapter batch)",
    ).file(mustExist = true, canBeDir = false)

    private val outputTrace by option(
        "--out",
        help = "Path to write the generated trace JSON after batch/mcp run",
    ).path()

    private val restoreFrom by option(
        "--restore",
        help = "Path to a snapshot JSON file to restore session from",
    ).file(mustExist = true, canBeDir = false)

    private val snapshotOut by option(
        "--snapshot-out",
        help = "Path to write a snapshot JSON when the session ends",
    ).path()

    private val migrationPolicyName by option(
        "--migration",
        help = "Snapshot migration policy: reject, fingerprint (default), vertices",
    ).choice("reject", "fingerprint", "vertices").default("fingerprint")

    @Suppress("unused")
    private val epochClock by option(
        "--epoch-clock",
        help = "Use deterministic epoch clock (reserved for future use)",
    ).flag()

    override fun help(context: Context): String = "Runs a kUML state machine or activity with a live event adapter."

    override fun run() {
        val policy =
            when (migrationPolicyName) {
                "reject" -> MigrationPolicy.Reject
                "vertices" -> MigrationPolicy.AcceptIfVerticesPresent()
                else -> MigrationPolicy.AcceptIfFingerprintMatches
            }

        val scriptText = script.readText()
        val manager = RunSessionManager()
        val startResult =
            manager.start(
                scriptText = scriptText,
                scriptName = script.name,
                restoreFrom = restoreFrom,
                migrationPolicy = policy,
            )

        when (startResult) {
            is SessionResult.Error -> {
                System.err.println(startResult.message)
                throw ProgramResult(startResult.exitCode)
            }

            is SessionResult.Ok -> echo(startResult.message ?: "Session started")
            is SessionResult.Terminated -> echo("Session already terminated at start")
        }

        val exitCode =
            when (adapter) {
                "chain-evm" -> {
                    val rpcUrl =
                        rpc ?: run {
                            System.err.println("--rpc <url> is required for --adapter chain-evm")
                            throw ProgramResult(ExitCodes.USAGE)
                        }
                    val contractAddr =
                        contract ?: run {
                            System.err.println("--contract <address> is required for --adapter chain-evm")
                            throw ProgramResult(ExitCodes.USAGE)
                        }
                    val rpcValidation = EvmUrlValidator.validateRpcUrl(rpcUrl)
                    if (rpcValidation is EvmUrlValidator.Result.Invalid) {
                        System.err.println("--rpc: ${rpcValidation.message}")
                        throw ProgramResult(ExitCodes.USAGE)
                    }
                    val contractValidation = EvmUrlValidator.validateContractAddress(contractAddr)
                    if (contractValidation is EvmUrlValidator.Result.Invalid) {
                        System.err.println("--contract: ${contractValidation.message}")
                        throw ProgramResult(ExitCodes.USAGE)
                    }
                    val options =
                        ChainEvmCliOptions(
                            rpcUrl = rpcUrl,
                            contractAddress = EvmUrlValidator.normalizeContract(contractAddr),
                            fromBlock = fromBlock,
                            chainId = chainId,
                        )
                    // Guard: kuml-runtime-chain-evm is JVM-only (web3j). The Native Image binary
                    // does not include it. Load EvmChainAdapter via reflection so that this class
                    // (RunCommand) can be compiled into the native image without web3j on the
                    // classpath. Identical pattern to ImportCommand.kt:133 and ExportCommand.kt:211.
                    val evmAdapterClass =
                        try {
                            Class.forName("dev.kuml.runtime.chain.evm.EvmChainAdapter")
                        } catch (_: ClassNotFoundException) {
                            System.err.println(
                                "chain-evm adapter requires the kUML Fat-JAR distribution.\n" +
                                    "Native Image binary does not include web3j (JVM-only).\n" +
                                    "Download the Fat-JAR from https://kuml.dev/releases",
                            )
                            throw ProgramResult(ExitCodes.FORMAT_NOT_AVAILABLE)
                        }
                    ChainEvmAdapterRunner(
                        manager = manager,
                        options = options,
                        scriptText = scriptText,
                        adapterFactory = {
                            @Suppress("UNCHECKED_CAST")
                            evmAdapterClass.getDeclaredConstructor().newInstance()
                                as dev.kuml.runtime.chain.KumlChainAdapter
                        },
                    ).run()
                }

                "batch" -> {
                    val eventsFile =
                        events ?: run {
                            System.err.println("--events <file> is required for --adapter batch")
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                    BatchAdapter(manager, eventsFile, outputTrace).run()
                }

                "mcp" -> {
                    val httpAdapter = McpHttpAdapter(manager, port)
                    val boundPort =
                        try {
                            httpAdapter.start()
                        } catch (e: McpPortBusyException) {
                            System.err.println(e.message)
                            throw ProgramResult(ExitCodes.RUN_PORT_BUSY)
                        }
                    echo("kuml run listening on http://localhost:$boundPort (adapter=mcp)")
                    httpAdapter.awaitTermination()
                    0
                }

                else -> StdinAdapter(manager).run()
            }

        snapshotOut?.let {
            try {
                manager.saveSnapshot(it)
                echo("Snapshot written to $it")
            } catch (e: Exception) {
                System.err.println("Failed to write snapshot: ${e.message}")
            }
        }

        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
