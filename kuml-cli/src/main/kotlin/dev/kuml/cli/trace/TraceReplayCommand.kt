package dev.kuml.cli.trace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.cli.ExitCodes
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.activity.ActivityDeadlockException
import dev.kuml.runtime.loadEvents
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.sysml2.Sysml2ActivityAdapter
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.trace.ActivityContextFromTrace
import dev.kuml.runtime.trace.ActivityTraceReplayer
import dev.kuml.runtime.trace.TraceFlavour
import dev.kuml.runtime.trace.TraceFlavourDetector
import dev.kuml.runtime.trace.TraceReplayer
import dev.kuml.runtime.trace.UnsupportedTraceFlavourException
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * `kuml trace replay <trace.json> <model.kuml.kts> [--verbose]`
 *
 * Replays an STM or Activity trace against its source model and diffs the result.
 * Routes automatically based on trace flavour detected via [TraceFlavourDetector].
 *
 * Exit codes:
 * - 0  — trace matches the replay exactly
 * - 7  — mismatch (TRACE_REPLAY_MISMATCH)
 * - 8  — EMPTY or MIXED trace (TRACE_UNSUPPORTED_FLAVOUR)
 * - 2  — script compilation error (SCRIPT_ERROR)
 * - 3  — I/O error (IO_ERROR)
 */
internal class TraceReplayCommand : CliktCommand(name = "replay") {
    private val traceFile by argument(help = "Path to the recorded trace JSON file")
        .file(mustExist = true, canBeDir = false)

    private val script by argument(help = "Path to the *.kuml.kts state-machine or activity script")
        .file(mustExist = true, canBeDir = false)

    private val verbose by option("--verbose", "-v", help = "Print detailed diff output").flag()

    private val eventsFile by option(
        "--events",
        help = "Events JSON for activity replay; first event's payload becomes eventContext.",
    ).file(mustExist = true, canBeDir = false)

    private val contextEntries by option(
        "--context",
        help = "key=value for activity eventContext (repeatable).",
    ).multiple()

    private val maxSteps by option(
        "--max-steps",
        help = "Max steps for activity replay (default 1000).",
    ).int().default(1000)

    override fun help(context: Context): String = "Replay a recorded STM or Activity trace against its source model and compare the result."

    override fun run() {
        // Load trace
        val traceData =
            try {
                loadTrace(traceFile)
            } catch (e: Exception) {
                System.err.println("Failed to load trace: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        // Detect flavour
        val flavour = TraceFlavourDetector.detect(traceData)

        // Evaluate script
        val scriptResult = evalScript(script)

        // Route by flavour
        when (flavour) {
            TraceFlavour.STM -> runStmReplay(scriptResult, traceData)
            TraceFlavour.ACTIVITY -> runActivityReplay(scriptResult, traceData)
            TraceFlavour.EMPTY, TraceFlavour.MIXED, TraceFlavour.AI -> {
                System.err.println(
                    "Unsupported trace flavour '$flavour': " +
                        "only STM and ACTIVITY traces can be replayed. " +
                        "EMPTY traces have no entries; MIXED traces contain both STM and Activity entries. " +
                        "AI traces contain AI-patch lifecycle entries and are not replayable.",
                )
                throw ProgramResult(ExitCodes.TRACE_UNSUPPORTED_FLAVOUR)
            }
        }
    }

    // ── STM replay ────────────────────────────────────────────────────────────

    private fun runStmReplay(
        scriptResult: ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>,
        traceData: TraceFile,
    ) {
        val sm = resolveStateMachine(scriptResult, script)

        val report =
            try {
                TraceReplayer().replay(sm, traceData)
            } catch (e: UnsupportedTraceFlavourException) {
                System.err.println("Unsupported trace flavour: ${e.message}")
                throw ProgramResult(ExitCodes.TRACE_UNSUPPORTED_FLAVOUR)
            } catch (e: IllegalArgumentException) {
                System.err.println("Replay error: ${e.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        echo(report.toHumanReadable(verbose = verbose))

        if (!report.isMatch) {
            throw ProgramResult(ExitCodes.TRACE_REPLAY_MISMATCH)
        }
    }

    // ── Activity replay ───────────────────────────────────────────────────────

    private fun runActivityReplay(
        scriptResult: ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>,
        traceData: TraceFile,
    ) {
        val extracted = extractDiagram(scriptResult, script)

        // Resolve ACT diagram
        val (actDiagram, sysml2Model) =
            when (extracted) {
                is ExtractedDiagram.Sysml2 -> {
                    val diag =
                        extracted.diagram as? ActDiagram
                            ?: extracted.model.diagrams
                                .filterIsInstance<ActDiagram>()
                                .firstOrNull()
                            ?: run {
                                System.err.println(
                                    "SysML 2 script '${script.name}' declares no ActDiagram. " +
                                        "kuml trace replay for Activity traces requires an " +
                                        "`actDiagram(\"…\") { … }` block.",
                                )
                                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                            }
                    diag to extracted.model
                }
                else -> {
                    System.err.println(
                        "Activity trace replay requires a SysML 2 ACT script. " +
                            "Script '${script.name}' does not produce a SysML 2 model.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        val runtime =
            try {
                Sysml2ActivityAdapter.runtimeFor(sysml2Model, actDiagram)
            } catch (ex: IllegalArgumentException) {
                System.err.println("SysML 2 ACT adapter error: ${ex.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val eventContext = buildEventContext()

        val report =
            try {
                ActivityTraceReplayer().replay(
                    runtime = runtime,
                    original = traceData,
                    eventContext = eventContext,
                    maxSteps = maxSteps,
                    failOnDeadlock = true,
                    modelId = actDiagram.name,
                )
            } catch (e: ActivityDeadlockException) {
                System.err.println(
                    "Activity replay deadlocked: ${e.message}",
                )
                throw ProgramResult(ExitCodes.TRACE_REPLAY_MISMATCH)
            } catch (e: IllegalArgumentException) {
                System.err.println("Replay error: ${e.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        echo(report.toHumanReadable(verbose = verbose))

        if (verbose && !report.isMatch) {
            val ctx = ActivityContextFromTrace.extract(traceData)
            echo("")
            echo(ctx.toHumanReadable())
        }

        if (!report.isMatch) {
            throw ProgramResult(ExitCodes.TRACE_REPLAY_MISMATCH)
        }
    }

    // ── event context builder ─────────────────────────────────────────────────

    /**
     * Build the eventContext map for activity replay from --events and --context options.
     *
     * Priority: --context entries override --events payload values.
     *
     * JSON primitive conversion mirrors SimulateCommand exactly:
     *   JsonPrimitive string → v.content
     *   JsonPrimitive non-string → toBooleanStrictOrNull() ?: toLongOrNull() ?: toDoubleOrNull() ?: content
     */
    private fun buildEventContext(): Map<String, Any> {
        // Base from --events file (first event's payload)
        val base: Map<String, Any> =
            eventsFile?.let { file ->
                try {
                    val evs = loadEvents(file)
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
                } catch (e: Exception) {
                    System.err.println("Failed to load events file: ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            } ?: emptyMap()

        // Overrides from --context key=value entries
        val overrides: Map<String, Any> =
            contextEntries.associate { entry ->
                val eq = entry.indexOf('=')
                if (eq < 0) {
                    System.err.println("Invalid --context entry '$entry': expected key=value format.")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                val key = entry.substring(0, eq)
                val raw = entry.substring(eq + 1)
                val value: Any =
                    raw.toBooleanStrictOrNull()
                        ?: raw.toLongOrNull()
                        ?: raw.toDoubleOrNull()
                        ?: raw
                key to value
            }

        return base + overrides
    }

    // ── script evaluation helpers ─────────────────────────────────────────────

    private fun evalScript(file: java.io.File): ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult> {
        val result = KumlScriptHost.eval(file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        @Suppress("UNCHECKED_CAST")
        return result as ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>
    }

    private fun extractDiagram(
        result: ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>,
        file: java.io.File,
    ): ExtractedDiagram =
        try {
            DiagramExtractor.extractAny(result.value.returnValue, file)
        } catch (_: Throwable) {
            val diagram = DiagramExtractor.extract(result.value.returnValue, file)
            ExtractedDiagram.Uml(diagram)
        }

    private fun resolveStateMachine(
        result: ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>,
        file: java.io.File,
    ): UmlStateMachine {
        val extracted =
            try {
                DiagramExtractor.extractAny(result.value.returnValue, file)
            } catch (_: Throwable) {
                val diagram = DiagramExtractor.extract(result.value.returnValue, file)
                ExtractedDiagram.Uml(diagram)
            }

        return when (extracted) {
            is ExtractedDiagram.Uml -> {
                extracted.diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                    System.err.println(
                        "Script must produce exactly one UmlStateMachine. " +
                            "Got: ${extracted.diagram.elements.map { it::class.simpleName }}",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

            is ExtractedDiagram.Sysml2 -> {
                val stm =
                    extracted.diagram as? StmDiagram
                        ?: extracted.model.diagrams
                            .filterIsInstance<StmDiagram>()
                            .firstOrNull()
                        ?: run {
                            System.err.println(
                                "SysML 2 script '${file.name}' declares no StmDiagram. " +
                                    "kuml trace replay requires a `stmDiagram(\"…\") { … }` block.",
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
                System.err.println("C4 diagrams have no executable behaviour and cannot be replayed.")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            is ExtractedDiagram.Bpmn -> {
                System.err.println("BPMN diagrams are not supported by `kuml trace replay`.")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }
    }
}
