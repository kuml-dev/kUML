package dev.kuml.cli.trace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.ExitCodes
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.trace.TraceReplayer
import dev.kuml.runtime.trace.UnsupportedTraceFlavourException
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * `kuml trace replay <trace.json> <model.kuml.kts> [--verbose]`
 *
 * Replays an STM trace against its source model and diffs the result.
 *
 * Exit codes:
 * - 0  — trace matches the replay exactly
 * - 7  — mismatch (TRACE_REPLAY_MISMATCH)
 * - 8  — Activity-flavoured trace unsupported (TRACE_UNSUPPORTED_FLAVOUR)
 * - 2  — script compilation error (SCRIPT_ERROR)
 * - 3  — I/O error (IO_ERROR)
 */
internal class TraceReplayCommand : CliktCommand(name = "replay") {
    private val traceFile by argument(help = "Path to the recorded trace JSON file")
        .file(mustExist = true, canBeDir = false)

    private val script by argument(help = "Path to the *.kuml.kts state-machine script")
        .file(mustExist = true, canBeDir = false)

    private val verbose by option("--verbose", "-v", help = "Print detailed diff output").flag()

    override fun help(context: Context): String = "Replay a recorded STM trace against its source model and compare the result."

    override fun run() {
        // Load trace
        val traceData =
            try {
                loadTrace(traceFile)
            } catch (e: Exception) {
                System.err.println("Failed to load trace: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        // Evaluate script
        val scriptResult = evalScript(script)

        // Extract state machine
        val sm = resolveStateMachine(scriptResult, script)

        // Replay
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

    // ── helpers ───────────────────────────────────────────────────────────────

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
        }
    }
}
