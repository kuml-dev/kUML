package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
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
import dev.kuml.runtime.activity.ActivityDeadlockException
import dev.kuml.runtime.loadEvents
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.sandbox.EffectExecutor
import dev.kuml.runtime.sandbox.SandboxEffectInvoker
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator
import dev.kuml.runtime.sysml2.Sysml2ActivityAdapter
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.writeTrace
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `simulate` subcommand — V1.1.5 (UML) + V2.0.17 (SysML 2 STM) + V2.0.18 (SysML 2 ACT).
 *
 * Two modes:
 *  - File mode: `kuml simulate <script> <events.json> --out <trace.json>`
 *  - Interactive mode: `kuml simulate <script> --interactive` (STM only)
 *
 * Optional `--expected <trace.json>` compares the produced trace to a goldfile.
 * `--epoch-clock` makes timestamps deterministic for reproducible Goldfile-Tests.
 * `--max-steps <N>` guards against infinite-loop ACT models (default 1000).
 *
 * ## Script flavours
 *
 *  * **UML scripts** — top-level expression is a `umlModel { … stateMachine { … } }`
 *    DSL that produces a [dev.kuml.uml.UmlStateMachine]. Loaded via
 *    [DiagramExtractor.extract] and passed directly to [StateMachineRuntime].
 *  * **SysML 2 STM scripts** — top-level expression is
 *    `sysml2Model("…") { … stmDiagram("…") { … } }`. V2.0.17 translates the
 *    selected [dev.kuml.sysml2.StateDefinition]s + [dev.kuml.sysml2.TransitionUsage]s
 *    to a [dev.kuml.uml.UmlStateMachine] via
 *    [Sysml2StateMachineAdapter.toUmlStateMachine] and runs them through the
 *    same [StateMachineRuntime].
 *  * **SysML 2 ACT scripts** — top-level expression is
 *    `sysml2Model("…") { … actDiagram("…") { … } }`. V2.0.18 builds an
 *    [dev.kuml.runtime.activity.ActivityRuntime] via [Sysml2ActivityAdapter]
 *    and runs the token-flow interpreter to completion. The events file
 *    provides the eventContext for guard evaluation; the first event's payload
 *    fields are used as the context map.
 *
 * If a SysML 2 script declares multiple diagrams, priority is:
 *  1. First [ActDiagram] (V2.0.18 — activity takes precedence over STM when both present)
 *  2. First [StmDiagram] (V2.0.17)
 *
 * The CLI's input / output contract (events file in, trace file out) is
 * identical across all flavours.
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

    private val maxSteps by option(
        "--max-steps",
        help = "Maximum steps for ACT activity execution (default 1000 — guard against infinite loops)",
    ).int().default(1000)

    private val sandbox by option(
        "--sandbox",
        help = "Enable sandbox execution (EffectExecutor + TimeLimitedGuardEvaluator).",
    ).flag()

    private val guardTimeoutMs by option(
        "--guard-timeout-ms",
        help = "Guard evaluation timeout in milliseconds when --sandbox is active (default ${SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS}).",
    ).long().default(SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS)

    override fun help(context: Context): String =
        "Execute a kUML or SysML 2 state machine / activity against an event sequence (file or REPL)."

    override fun run() {
        // Evaluate script and dispatch to the appropriate runtime
        val scriptResult = evalScript(script)
        val extracted = extractDiagram(scriptResult, script)

        // Check if this is an ACT diagram — route to activity runtime
        if (extracted is ExtractedDiagram.Sysml2) {
            val actDiagram =
                extracted.diagram as? ActDiagram
                    ?: extracted.model.diagrams
                        .filterIsInstance<ActDiagram>()
                        .firstOrNull()
            if (actDiagram != null) {
                runActivity(extracted, actDiagram)
                return
            }
        }

        // STM / UML path (existing)
        val sm = resolveStateMachine(extracted, script)
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
        val sandboxPolicy = SandboxPolicy(guardTimeoutMs = guardTimeoutMs)
        val guardsEvaluator =
            if (sandbox) {
                TimeLimitedGuardEvaluator(OclGuardEvaluator(), sandboxPolicy)
            } else {
                OclGuardEvaluator()
            }
        val effectInvoker =
            if (sandbox) {
                SandboxEffectInvoker(EffectExecutor(sandboxPolicy))
            } else {
                dev.kuml.runtime.EffectInvoker.NoOp
            }
        val runtime = StateMachineRuntime(guards = guardsEvaluator, clock = clock, effects = effectInvoker)
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

    // ── ACT execution path ────────────────────────────────────────────────────

    private fun runActivity(
        extracted: ExtractedDiagram.Sysml2,
        diagram: ActDiagram,
    ) {
        val eventFile =
            events ?: run {
                System.err.println("EVENTS argument required to run an ACT activity.")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val evs =
            try {
                loadEvents(eventFile)
            } catch (e: Exception) {
                System.err.println("Failed to load events: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        // Build event context from the first event's payload (flat map)
        val eventContext: Map<String, Any> =
            evs.firstOrNull()?.let { firstEvent ->
                firstEvent.payload.mapValues { (_, v) ->
                    when {
                        v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                        v is kotlinx.serialization.json.JsonPrimitive ->
                            v.content.toBooleanStrictOrNull()
                                ?: v.content.toLongOrNull()
                                ?: v.content.toDoubleOrNull()
                                ?: v.content
                        else -> v.toString()
                    }
                }
            } ?: emptyMap()

        val runtime =
            try {
                Sysml2ActivityAdapter.runtimeFor(extracted.model, diagram)
            } catch (ex: IllegalArgumentException) {
                System.err.println("SysML 2 ACT adapter error: ${ex.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val (initialInstance, startTrace) = runtime.start(eventContext)

        val (finalInstance, runTrace) =
            try {
                runtime.run(
                    initial = initialInstance,
                    eventContext = eventContext,
                    maxSteps = maxSteps,
                    failOnDeadlock = true,
                )
            } catch (ex: ActivityDeadlockException) {
                System.err.println("Activity error: ${ex.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val allTrace = startTrace + runTrace
        val steps = finalInstance.clock

        echo("Activity terminated after $steps steps, ${allTrace.size} trace entries")

        outputTrace?.let {
            try {
                writeTrace(allTrace, it.toFile(), modelId = diagram.name)
                echo("Wrote ${allTrace.size} trace entries to $it")
            } catch (e: IOException) {
                System.err.println("I/O error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }
        }

        expectedTrace?.let { exp ->
            val expected = loadTrace(exp).entries
            val report = TraceDiff.compare(allTrace, expected)
            if (!report.isMatch) {
                System.err.println(report.toHumanReadable())
                throw ProgramResult(ExitCodes.TRACE_DIFF)
            } else {
                echo("Trace matches expected (${report.matched} entries).")
            }
        }
    }

    // ── script evaluation helpers ─────────────────────────────────────────────

    private fun evalScript(file: java.io.File): ResultWithDiagnostics.Success<EvaluationResult> {
        val result = KumlScriptHost.eval(file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        @Suppress("UNCHECKED_CAST")
        return result as ResultWithDiagnostics.Success<EvaluationResult>
    }

    private fun extractDiagram(
        result: ResultWithDiagnostics.Success<EvaluationResult>,
        file: java.io.File,
    ): ExtractedDiagram =
        try {
            dev.kuml.core.script.DiagramExtractor
                .extractAny(result.value.returnValue, file)
        } catch (_: Throwable) {
            // Legacy UML path: older `stateMachine { … }` scripts that don't wrap in sysml2Model
            val diagram = DiagramExtractor.extract(result.value.returnValue, file)
            ExtractedDiagram.Uml(diagram)
        }

    /**
     * Resolve a [UmlStateMachine] from an [ExtractedDiagram]. Used for STM/UML paths only.
     * ACT paths are handled separately by [runActivity].
     */
    private fun resolveStateMachine(
        extracted: ExtractedDiagram,
        file: java.io.File,
    ): UmlStateMachine =
        when (extracted) {
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
                // ACT diagrams were already handled in run() before reaching here.
                // Here we only handle STM diagrams.
                val stm =
                    extracted.diagram as? StmDiagram
                        ?: extracted.model.diagrams
                            .filterIsInstance<StmDiagram>()
                            .firstOrNull()
                        ?: run {
                            System.err.println(
                                "SysML 2 script '${file.name}' declares no StmDiagram or ActDiagram. " +
                                    "`kuml simulate` requires a `stmDiagram(\"…\") { … }` or " +
                                    "`actDiagram(\"…\") { … }` block to identify which diagram to simulate.",
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
                        "Use a UML or SysML 2 STM/ACT script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
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
