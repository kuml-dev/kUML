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
 *
 * ## Snapshot support
 *  - `--restore <snapshot.json>`: restore session from a previous snapshot
 *  - `--snapshot-out <path>`: write snapshot when session ends
 *  - `--migration`: policy for snapshot compatibility (reject|fingerprint|vertices)
 */
internal class RunCommand : CliktCommand(name = "run") {
    private val script by argument(help = "Path to *.kuml.kts state-machine or activity script")
        .file(mustExist = true, canBeDir = false)

    private val adapter by option(
        "--adapter",
        help = "Event adapter: stdin (interactive), mcp (HTTP server), batch (file-driven)",
    ).choice("stdin", "mcp", "batch").default("stdin")

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

        val manager = RunSessionManager()
        val startResult =
            manager.start(
                scriptText = script.readText(),
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
