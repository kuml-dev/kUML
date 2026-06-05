package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.Event
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceDiff
import dev.kuml.runtime.loadEvents
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.writeTrace
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `simulate` subcommand — V1.1.5.
 *
 * Two modes:
 *  - File mode: `kuml simulate <script> <events.json> --out <trace.json>`
 *  - Interactive mode: `kuml simulate <script> --interactive`
 *
 * Optional `--expected <trace.json>` compares the produced trace to a goldfile.
 * `--epoch-clock` makes timestamps deterministic for reproducible Goldfile-Tests.
 */
internal class SimulateCommand : CliktCommand(name = "simulate") {
    private val script by argument(help = "Path to *.kuml.kts state-machine script")
        .file(mustExist = true, canBeDir = false)

    private val events by argument(help = "Path to events JSON (omit when using --interactive)")
        .file(mustExist = true, canBeDir = false)
        .optional()

    private val outputTrace by option("--out", help = "Path to write the generated trace JSON")
        .path()

    private val expectedTrace by option("--expected", help = "Path to expected trace JSON for diff")
        .file(mustExist = true, canBeDir = false)

    private val interactive by option("--interactive", help = "Run an interactive REPL").flag()

    private val epochClock by option(
        "--epoch-clock",
        help = "Use deterministic epoch clock for reproducible tests",
    ).flag()

    override fun help(context: Context): String = "Execute a kUML state machine against an event sequence (file or REPL)."

    override fun run() {
        val sm = loadStateMachine(script)
        val clock: () -> Instant =
            if (epochClock) {
                val counter =
                    java.util.concurrent.atomic
                        .AtomicLong(0L)
                val fn: () -> Instant = { Instant.ofEpochMilli(counter.getAndIncrement()) }
                fn
            } else {
                Instant::now
            }
        val runtime = StateMachineRuntime(guards = OclGuardEvaluator(), clock = clock)
        val instance = runtime.start(sm)

        if (interactive) {
            runInteractive(runtime, instance)
        } else {
            val eventFile =
                events ?: run {
                    System.err.println("EVENTS argument required when --interactive is not set.")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            val evs =
                try {
                    loadEvents(eventFile)
                } catch (e: Exception) {
                    System.err.println("Failed to load events: ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            for (ev in evs) {
                if (instance.isTerminated) break
                runtime.step(instance, ev)
            }
            outputTrace?.let {
                try {
                    writeTrace(instance.trace, it.toFile(), modelId = sm.id)
                    echo("Wrote ${instance.trace.size} trace entries to $it")
                } catch (e: IOException) {
                    System.err.println("I/O error: ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            }
            expectedTrace?.let { exp ->
                val expected = loadTrace(exp).entries
                val report = TraceDiff.compare(instance.trace, expected)
                if (!report.isMatch) {
                    System.err.println(report.toHumanReadable())
                    throw ProgramResult(ExitCodes.TRACE_DIFF)
                } else {
                    echo("Trace matches expected (${report.matched} entries).")
                }
            }
        }
    }

    private fun loadStateMachine(file: java.io.File): UmlStateMachine {
        val result = KumlScriptHost.eval(file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success = result as ResultWithDiagnostics.Success
        val diagram = DiagramExtractor.extract(success.value.returnValue, file)
        val sm =
            diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                System.err.println(
                    "Script must produce exactly one UmlStateMachine in its diagram. " +
                        "Got: ${diagram.elements.map { it::class.simpleName }}",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        return sm
    }

    private fun runInteractive(
        runtime: StateMachineRuntime,
        instance: StateMachineInstance,
    ) {
        echo("Loaded state machine: '${instance.model.name}'")
        echo("Currently in: ${instance.currentVertices.map { it.id }}")
        while (true) {
            val line =
                try {
                    readlnOrNull()?.trim() ?: break
                } catch (_: Exception) {
                    break
                }
            if (line.isEmpty()) continue
            if (line == "quit" || line == "exit") break
            val (name, payload) = parseInteractive(line)
            val result = runtime.step(instance, Event(name = name, payload = payload))
            when (result) {
                is StepResult.Transitioned ->
                    echo("─ Transitioned: ${result.fromVertexIds} → ${result.toVertexIds}")
                is StepResult.Stayed -> echo("─ Stayed: ${result.reason}")
                is StepResult.GuardFailed -> echo("─ GuardFailed on ${result.transitionId}: ${result.message}")
                is StepResult.Error -> echo("─ Error: ${result.cause.message}")
                StepResult.Terminated -> echo("─ Terminated.")
            }
            echo("  Currently in: ${instance.currentVertices.map { it.id }}")
            if (instance.isTerminated) {
                echo("State machine terminated.")
                break
            }
        }
        outputTrace?.let {
            writeTrace(instance.trace, it.toFile(), modelId = instance.model.id)
            echo("Wrote ${instance.trace.size} trace entries to $it")
        }
    }

    private fun parseInteractive(line: String): Pair<String, JsonObject> {
        val ws = line.indexOf(' ')
        if (ws < 0) return line to JsonObject(emptyMap())
        val name = line.substring(0, ws).trim()
        val rest = line.substring(ws).trim()
        val payload =
            try {
                KumlRuntimeJson.parseToJsonElement(rest).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
        return name to payload
    }
}
