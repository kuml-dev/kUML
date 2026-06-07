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
import dev.kuml.core.script.ExtractedDiagram
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
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.writeTrace
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `simulate` subcommand — V1.1.5 (UML) + V2.0.17 (SysML 2).
 *
 * Two modes:
 *  - File mode: `kuml simulate <script> <events.json> --out <trace.json>`
 *  - Interactive mode: `kuml simulate <script> --interactive`
 *
 * Optional `--expected <trace.json>` compares the produced trace to a goldfile.
 * `--epoch-clock` makes timestamps deterministic for reproducible Goldfile-Tests.
 *
 * ## Script flavours
 *
 *  * **UML scripts** — top-level expression is a `umlModel { … stateMachine { … } }`
 *    DSL that produces a [dev.kuml.uml.UmlStateMachine]. Loaded via
 *    [DiagramExtractor.extract] and passed directly to [StateMachineRuntime].
 *  * **SysML 2 scripts** — top-level expression is
 *    `sysml2Model("…") { … stmDiagram("…") { … } }`. V2.0.17 translates the
 *    selected [dev.kuml.sysml2.StateDefinition]s + [dev.kuml.sysml2.TransitionUsage]s
 *    to a [dev.kuml.uml.UmlStateMachine] via
 *    [Sysml2StateMachineAdapter.toUmlStateMachine] and runs them through the
 *    same [StateMachineRuntime]. The CLI's input / output contract (events
 *    file in, trace file out) is identical.
 *
 * If a SysML 2 script declares multiple diagrams, the **first [StmDiagram]**
 * in declaration order is used. Non-STM diagrams (BDD, IBD, UC, REQ, ACT,
 * SEQ, PAR) are not simulatable and cause a script-error exit.
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

    override fun help(context: Context): String = "Execute a kUML or SysML 2 state machine against an event sequence (file or REPL)."

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

    /**
     * Load a [UmlStateMachine] from a script file, transparently handling
     * both UML and SysML 2 flavours (V2.0.17).
     *
     * Dispatch order:
     *  1. Try [dev.kuml.core.script.DiagramExtractor.extractAny] — this
     *     yields either an [ExtractedDiagram.Uml], [ExtractedDiagram.C4] or
     *     [ExtractedDiagram.Sysml2].
     *  2. UML branch: pull the single [UmlStateMachine] out of the
     *     extracted [dev.kuml.core.model.KumlDiagram].
     *  3. SysML 2 branch: require an [StmDiagram] (other SysML 2 diagram
     *     kinds are not simulatable) and translate via
     *     [Sysml2StateMachineAdapter.toUmlStateMachine]. If the matched
     *     SysML 2 diagram is not the first declared one, the model's
     *     `diagrams` list is scanned for the first [StmDiagram] in
     *     declaration order so authors can mix STM with structural
     *     diagrams in the same script.
     *  4. C4 branch: hard error — C4 diagrams have no executable behaviour.
     */
    private fun loadStateMachine(file: java.io.File): UmlStateMachine {
        val result = KumlScriptHost.eval(file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success = result as ResultWithDiagnostics.Success

        val extracted =
            try {
                dev.kuml.core.script.DiagramExtractor
                    .extractAny(success.value.returnValue, file)
            } catch (_: Throwable) {
                // Fall back to the V1.1.5 path: pure UML scripts whose
                // top-level expression isn't yet a `KumlDiagram` (e.g. older
                // `stateMachine { … }` shapes) are still loadable by the
                // legacy extractor.
                val diagram = DiagramExtractor.extract(success.value.returnValue, file)
                return diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                    System.err.println(
                        "Script must produce exactly one UmlStateMachine in its diagram. " +
                            "Got: ${diagram.elements.map { it::class.simpleName }}",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        return when (extracted) {
            is ExtractedDiagram.Uml -> {
                val diagram = extracted.diagram
                diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                    System.err.println(
                        "Script must produce exactly one UmlStateMachine in its diagram. " +
                            "Got: ${diagram.elements.map { it::class.simpleName }}",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }
            is ExtractedDiagram.Sysml2 -> {
                // The first diagram in declaration order may not be an STM —
                // a script can declare a BDD then an STM. Scan for the first
                // STM so authors can mix structural + behavioural diagrams
                // in the same script.
                val stm =
                    extracted.diagram as? StmDiagram
                        ?: extracted.model.diagrams
                            .filterIsInstance<StmDiagram>()
                            .firstOrNull()
                        ?: run {
                            System.err.println(
                                "SysML 2 script '${file.name}' declares no StmDiagram. " +
                                    "`kuml simulate` requires an `stmDiagram(\"…\") { … }` block " +
                                    "to identify which states to simulate.",
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                try {
                    Sysml2StateMachineAdapter.toUmlStateMachine(extracted.model, stm)
                } catch (ex: IllegalStateException) {
                    System.err.println("SysML 2 STM adapter error: ${ex.message}")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }
            is ExtractedDiagram.C4 -> {
                System.err.println(
                    "C4 diagrams have no executable behaviour and cannot be simulated. " +
                        "Use a UML or SysML 2 STM script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }
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
