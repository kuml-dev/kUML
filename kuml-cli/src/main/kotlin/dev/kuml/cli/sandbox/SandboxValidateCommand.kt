package dev.kuml.cli.sandbox

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import dev.kuml.cli.ExitCodes
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.SandboxValidator
import dev.kuml.runtime.sandbox.ViolationKind
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * `kuml sandbox validate <model.kuml.kts>` — static analysis of a kUML model
 * against the sandbox policy.
 *
 * Exit codes:
 * - 0: no violations
 * - 2: script/extraction error
 * - 12: one or more policy violations found
 *
 * V2.0.40 — Sandbox-Garantien.
 */
internal class SandboxValidateCommand : CliktCommand(name = "validate") {
    private val script by argument(help = "Path to *.kuml.kts state-machine script")
        .file(mustExist = true, canBeDir = false)

    private val strict by option(
        "--strict",
        help = "Use the strict sandbox policy preset (short timeout, no built-ins, minimal limits).",
    ).flag(default = false)

    private val guardTimeoutMs by option(
        "--guard-timeout-ms",
        help = "Guard evaluation timeout in milliseconds (default ${SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS}).",
    ).long().default(SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS)

    override fun help(context: Context): String =
        "Statically validate a kUML model against the sandbox policy — checks guards and action bodies."

    override fun run() {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            echo("Script error: ${errors.joinToString("\n") { it.message }}", err = true)
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        @Suppress("UNCHECKED_CAST")
        val success =
            evalResult as? ResultWithDiagnostics.Success<EvaluationResult>
                ?: run {
                    echo("Script evaluation produced no result", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        // 2. Extract state machine
        val sm = extractStateMachine(success)

        // 3. Build policy
        val policy =
            if (strict) {
                SandboxPolicy.Strict
            } else {
                SandboxPolicy(guardTimeoutMs = guardTimeoutMs)
            }

        // 4. Validate
        val validator = SandboxValidator(policy)
        val report = validator.validate(sm)

        if (report.isClean) {
            echo("Sandbox validation passed — no violations found.")
            return
        }

        echo("Sandbox violations (${report.violations.size}):")
        for (v in report.violations) {
            val loc =
                listOfNotNull(
                    v.location.vertexId?.let { "vertex=$it" },
                    v.location.transitionId?.let { "transition=$it" },
                    v.location.phase?.let { "phase=$it" },
                ).joinToString(", ").ifEmpty { "(unknown)" }
            val severity = if (v.kind == ViolationKind.PARSE_ERROR) "WARN " else "ERROR"
            echo("  $severity [$loc] ${v.message}")
        }

        val errorCount = report.violations.count { it.kind != ViolationKind.PARSE_ERROR }
        if (errorCount > 0) {
            throw ProgramResult(ExitCodes.SANDBOX_VIOLATIONS)
        }
    }

    private fun extractStateMachine(success: ResultWithDiagnostics.Success<EvaluationResult>): UmlStateMachine {
        val extracted: ExtractedDiagram =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, script)
            } catch (_: Throwable) {
                try {
                    val diagram = DiagramExtractor.extract(success.value.returnValue, script)
                    ExtractedDiagram.Uml(diagram)
                } catch (ex: Throwable) {
                    echo("Could not extract diagram from script: ${ex.message}", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        return when (extracted) {
            is ExtractedDiagram.Uml -> {
                extracted.diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                    echo(
                        "Script must produce exactly one UmlStateMachine. " +
                            "Got: ${extracted.diagram.elements.map { it::class.simpleName }}",
                        err = true,
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
                            echo(
                                "SysML 2 script '${script.name}' declares no StmDiagram. " +
                                    "Sandbox validate requires a state-machine diagram.",
                                err = true,
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                try {
                    Sysml2StateMachineAdapter.toUmlStateMachine(extracted.model, stm)
                } catch (ex: IllegalStateException) {
                    echo("SysML 2 STM adapter error: ${ex.message}", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }
            is ExtractedDiagram.C4 -> {
                echo("C4 diagrams have no executable behaviour and cannot be sandbox-validated.", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            is ExtractedDiagram.Bpmn -> {
                echo("BPMN diagrams are not supported by `kuml sandbox validate`. Use a UML or SysML 2 STM script instead.", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }
    }
}
