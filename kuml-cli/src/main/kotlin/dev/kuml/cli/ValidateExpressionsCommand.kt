package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.expr.ExpressionTypeChecker
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.expr.ParseError
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `validate-expressions` subcommand (V2.0.20a).
 *
 * Loads a `*.kuml.kts` script, extracts all guard/effect strings from the
 * model (STM transition guards, ACT control-flow guards), attempts to parse
 * each one with [OclLikeExpressionParser], and prints a summary table or JSON
 * array of results.
 *
 * Exit codes:
 * - 0: all guards parsed successfully (or no guards found)
 * - 5: one or more guards could not be parsed (or `--strict` + any warning)
 * - 3: script evaluation error
 */
internal class ValidateExpressionsCommand : CliktCommand(name = "validate-expressions") {
    private val script by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val strict by option(
        "--strict",
        help = "Fail (exit non-zero) on any parse warning (not just hard errors)",
    ).flag()

    private val jsonOutput by option(
        "--json",
        help = "Output results as a JSON array",
    ).flag()

    override fun help(context: Context): String =
        "DEPRECATED: use 'kuml validate --strict' instead. Will be removed in v0.7. " +
            "Validate guard/effect expressions in a kUML or SysML 2 script (V2.0.20a)."

    override fun run() {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            echo("Script error: ${errors.joinToString("\n") { it.message }}", err = true)
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    echo("Script evaluation produced no result", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        // 2. Extract diagram
        val extracted: ExtractedDiagram =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, script)
            } catch (_: Throwable) {
                try {
                    ExtractedDiagram.Uml(
                        dev.kuml.cli.DiagramExtractor
                            .extract(success.value.returnValue, script),
                    )
                } catch (e: ScriptEvaluationException) {
                    echo("Script error: ${e.message}", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        // 3. Collect guard strings
        val guards = collectGuards(extracted)

        // 4. Attempt parse on each
        val results =
            guards.map { (source, expression) ->
                val parseErrors = mutableListOf<ParseError>()
                val parsed = OclLikeExpressionParser.tryParse(expression, parseErrors)
                val inferred =
                    if (parsed != null) {
                        ExpressionTypeChecker.infer(parsed).javaClass.simpleName
                    } else {
                        null
                    }
                ExpressionCheckResult(
                    source = source,
                    expression = expression,
                    parsed = parsed != null,
                    type = inferred,
                    error = parseErrors.firstOrNull()?.message,
                )
            }

        // 5. Output
        if (jsonOutput) {
            echo(Json { prettyPrint = true }.encodeToString(results))
        } else {
            printTable(results)
        }

        // 6. Exit code
        val anyFailed = results.any { !it.parsed }
        if (anyFailed || (strict && results.any { it.error != null })) {
            throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        }
    }

    private fun collectGuards(extracted: ExtractedDiagram): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                // UML: inspect stateMachines in the diagram
                extracted.diagram.elements
                    .filterIsInstance<UmlStateMachine>()
                    .forEach { sm ->
                        sm.transitions.collectTransitionGuards(result)
                    }
            }
            is ExtractedDiagram.Sysml2 -> {
                // SysML 2: TransitionUsage guards (STM) + ControlFlowUsage guards (ACT)
                extracted.model.usages.filterIsInstance<TransitionUsage>().forEach { tu ->
                    tu.guard?.let { result += "transition:${tu.id}" to it }
                    tu.effect?.let { result += "effect:${tu.id}" to it }
                }
                extracted.model.usages.filterIsInstance<ControlFlowUsage>().forEach { cf ->
                    cf.guard?.let { result += "controlFlow:${cf.id}" to it }
                }
            }
            is ExtractedDiagram.C4 -> {
                // C4 diagrams have no executable guards
            }
            is ExtractedDiagram.Bpmn -> {
                // BPMN diagrams — sequence flow conditions are string expressions;
                // not checked here (no OCL-like type system for BPMN conditions).
            }
            is ExtractedDiagram.Blueprint -> {
                // Blueprint/Journey-Map diagrams have no guard expressions.
            }
        }
        return result
    }

    private fun List<UmlTransition>.collectTransitionGuards(into: MutableList<Pair<String, String>>) {
        forEach { tr ->
            tr.guard?.let { into += "transition:${tr.id}" to it }
        }
    }

    private fun printTable(results: List<ExpressionCheckResult>) {
        if (results.isEmpty()) {
            echo("${script.name}: no guard expressions found.")
            return
        }
        val passed = results.count { it.parsed }
        val failed = results.size - passed
        echo("${script.name}: ${results.size} expression(s) — $passed parsed, $failed failed\n")
        for (r in results) {
            val status = if (r.parsed) "OK   " else "FAIL "
            val detail = if (r.parsed) "(type: ${r.type ?: "?"})" else "(error: ${r.error ?: "unknown"})"
            echo("  $status [${r.source}] '${r.expression.take(60)}' $detail")
        }
        if (failed > 0) {
            echo("\n$failed expression(s) could not be parsed — will use legacy OCL evaluator at runtime.")
        } else {
            echo("\nAll expressions parsed successfully.")
        }
    }
}

@Serializable
internal data class ExpressionCheckResult(
    val source: String,
    val expression: String,
    val parsed: Boolean,
    val type: String?,
    val error: String?,
)
