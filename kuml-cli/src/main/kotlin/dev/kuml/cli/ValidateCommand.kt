package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.script.KumlScriptHost
import kotlinx.serialization.json.Json
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `validate` subcommand.
 *
 * Evaluates a `*.kuml.kts` script and checks all OCL constraints.
 *
 * Exit codes:
 * - 0: no violations
 * - 2: script error
 * - 4: violations found (see [ExitCodes.VALIDATION_VIOLATIONS])
 */
internal class ValidateCommand : CliktCommand(name = "validate") {
    private val input by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val outputFormat by option("-o", "--output", help = "Output format")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String = "Validate OCL constraints in a kUML script."

    override fun run() {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(input)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            System.err.println("Script error: ${errors.joinToString("\n") { it.message }}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    System.err.println("Script evaluation produced no result")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        // 2. Extract diagram
        val diagram =
            try {
                DiagramExtractor.extract(success.value.returnValue, input)
            } catch (e: ScriptEvaluationException) {
                System.err.println("Script error: ${e.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        // 3. Validate
        val result = OclValidator.validate(diagram)

        // 4. Output
        when (outputFormat) {
            "json" ->
                echo(Json { prettyPrint = true }.encodeToString(KumlValidationResult.serializer(), result))
            else -> printText(result)
        }

        if (!result.valid) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
    }

    private fun printText(result: KumlValidationResult) {
        if (result.valid) {
            echo("${input.name}: valid — no violations.")
            return
        }
        echo("Validating ${input.name}...\n")
        for (v in result.violations) {
            echo("Constraint violation on '${v.classifierName}' (constraint: '${v.constraintName}'):")
            echo("  OCL: ${v.oclExpression}")
            echo("  Result: ${v.message}\n")
        }
        echo("${result.violations.size} violation(s) found.")
    }
}
