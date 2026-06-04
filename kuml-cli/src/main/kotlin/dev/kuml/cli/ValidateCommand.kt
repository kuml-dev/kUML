package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.KumlViolation
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.ocl.StereotypeValidator
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.profile.ProfileRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `validate` subcommand.
 *
 * Evaluates a `*.kuml.kts` script and checks all OCL constraints.
 * When [checkStereotypes] is `true`, additionally runs [StereotypeValidator].
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

    private val checkStereotypes by option(
        "--check-stereotypes",
        help = "Additionally validate stereotype applications (required properties, OCL constraints). Default: off.",
    ).flag(default = false)

    override fun help(context: Context): String = "Validate OCL constraints in a kUML script."

    override fun run() {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(input)
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
        val diagram =
            try {
                DiagramExtractor.extract(success.value.returnValue, input)
            } catch (e: ScriptEvaluationException) {
                echo("Script error: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        // 3. Model OCL validation
        val modelResult = OclValidator.validate(diagram)

        // 4. Stereotype validation (opt-in)
        val stereotypeResult =
            if (checkStereotypes) {
                ProfileRegistry.loadFromClasspath()
                StereotypeValidator.validate(diagram)
            } else {
                null
            }

        // 5. Output
        val allViolations =
            modelResult.violations + (stereotypeResult?.violations ?: emptyList())
        val combined =
            KumlValidationResult(
                valid = allViolations.isEmpty(),
                violations = allViolations,
            )

        when (outputFormat) {
            "json" -> {
                if (checkStereotypes) {
                    // Split violations into model / stereotype sections for scriptability (D22)
                    val splitOutput =
                        ValidateJsonOutput(
                            valid = combined.valid,
                            violations =
                                ValidateViolationSplit(
                                    model = modelResult.violations,
                                    stereotype = stereotypeResult?.violations ?: emptyList(),
                                ),
                        )
                    echo(Json { prettyPrint = true }.encodeToString(splitOutput))
                } else {
                    echo(Json { prettyPrint = true }.encodeToString(KumlValidationResult.serializer(), modelResult))
                }
            }
            else -> printText(combined, modelResult.violations, stereotypeResult?.violations)
        }

        if (!combined.valid) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
    }

    private fun printText(
        combined: KumlValidationResult,
        modelViolations: List<KumlViolation>,
        stereotypeViolations: List<KumlViolation>?,
    ) {
        if (combined.valid) {
            echo("${input.name}: valid — no violations.")
            return
        }
        echo("Validating ${input.name}...\n")
        if (modelViolations.isNotEmpty()) {
            echo("Model OCL violations:")
            for (v in modelViolations) {
                echo("  Constraint violation on '${v.classifierName}' (constraint: '${v.constraintName}'):")
                echo("    OCL: ${v.oclExpression}")
                echo("    Result: ${v.message}\n")
            }
        }
        if (!stereotypeViolations.isNullOrEmpty()) {
            echo("Stereotype violations:")
            for (v in stereotypeViolations) {
                echo("  ${v.message}\n")
            }
        }
        echo("${combined.violations.size} violation(s) found.")
    }
}

// ── JSON output types for --check-stereotypes + --output json ────────────────

@Serializable
internal data class ValidateJsonOutput(
    val valid: Boolean,
    val violations: ValidateViolationSplit,
)

@Serializable
internal data class ValidateViolationSplit(
    @SerialName("model") val model: List<KumlViolation>,
    @SerialName("stereotype") val stereotype: List<KumlViolation>,
)
