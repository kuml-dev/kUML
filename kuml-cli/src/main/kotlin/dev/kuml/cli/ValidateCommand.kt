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
import dev.kuml.cli.validate.StructuralValidator
import dev.kuml.cli.validate.StructuralViolation
import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.KumlViolation
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.ocl.StereotypeValidator
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.expr.ExpressionTypeChecker
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.profile.ProfileRegistry
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.constraint.Sysml2ConstraintChecker
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

    private val strict by option(
        "--strict",
        help = "Fail on any expression parse warning in addition to OCL violations (V2.0.20b).",
    ).flag(default = false)

    private val noCheckStructure by option(
        "--no-check-structure",
        help = "Skip structural checks (duplicate IDs, circular inheritance, dangling references). Default: structural checks are ON.",
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

        // 2. Extract diagram — try the unified extractAny path first (handles UML, C4, SysML2),
        //    then fall back to the legacy UML-only path for backward compatibility.
        val extracted: ExtractedDiagram? =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, input)
            } catch (_: Throwable) {
                null
            }

        // For OCL validation we need a UML diagram (legacy path).
        // SysML 2 and C4 scripts skip OCL validation gracefully.
        val umlDiagram =
            when (extracted) {
                is ExtractedDiagram.Uml -> extracted.diagram
                else -> null
            } ?: run {
                // Attempt the legacy extract for backward compat — errors are silently swallowed
                // when the script is SysML 2 / C4 (those don't have OCL constraints).
                try {
                    DiagramExtractor.extract(success.value.returnValue, input)
                } catch (_: Throwable) {
                    null
                }
            }

        // 3. Model OCL validation — only if a UML diagram was extracted.
        val modelResult =
            if (umlDiagram != null) {
                OclValidator.validate(umlDiagram)
            } else {
                KumlValidationResult(valid = true, violations = emptyList())
            }

        // 4. Stereotype validation (opt-in, UML only)
        val stereotypeResult =
            if (checkStereotypes && umlDiagram != null) {
                ProfileRegistry.loadFromClasspath()
                StereotypeValidator.validate(umlDiagram)
            } else {
                null
            }

        // 5. Expression validation (V2.0.20b — always runs; strict controls exit code)
        val exprErrors = validateExpressions(extracted)
        if (exprErrors.isNotEmpty()) {
            echo("\nExpression validation:")
            for (msg in exprErrors) {
                echo("  WARN  $msg")
            }
            if (strict) {
                echo("\n${exprErrors.size} expression issue(s) found (--strict mode).")
            }
        }

        // 6. PAR constraint type-check (V2.0.20b)
        val constraintErrors = validateConstraints(extracted)
        if (constraintErrors.isNotEmpty()) {
            echo("\nConstraint type-check:")
            for (err in constraintErrors) {
                echo("  FAIL  [${err.constraintId}] '${err.expression}': ${err.message}")
            }
        }

        // 7. Structural validation (V2.0.31) — runs on UML diagrams; skip with --no-check-structure.
        //    Check 4 (missing required stereotype properties) requires profiles to be loaded.
        //    It is gated behind --check-stereotypes to stay backward-compatible with the existing
        //    contract that stereotype-related output is opt-in.
        val structuralViolations: List<StructuralViolation> =
            if (!noCheckStructure && umlDiagram != null) {
                val rawViolations = StructuralValidator.validate(umlDiagram)
                if (!checkStereotypes) {
                    // Without --check-stereotypes, suppress the stereotype-property check
                    // (MISSING_REQUIRED_STEREOTYPE_PROPERTY) to keep backward compatibility.
                    rawViolations.filter { it.id != "MISSING_REQUIRED_STEREOTYPE_PROPERTY" }
                } else {
                    rawViolations
                }
            } else {
                emptyList()
            }
        val structuralErrors = structuralViolations.filter { it.severity == "error" }
        val structuralWarnings = structuralViolations.filter { it.severity == "warning" }

        if (structuralViolations.isNotEmpty()) {
            echo("\nStructural validation:")
            for (sv in structuralErrors) {
                echo("  ERROR [${sv.id}] ${sv.message}")
            }
            for (sv in structuralWarnings) {
                echo("  WARN  [${sv.id}] ${sv.message}")
            }
        }

        // 8. Output
        val allViolations =
            modelResult.violations + (stereotypeResult?.violations ?: emptyList())
        val combined =
            KumlValidationResult(
                valid = allViolations.isEmpty() && structuralErrors.isEmpty(),
                violations = allViolations,
            )

        when (outputFormat) {
            "json" -> {
                if (checkStereotypes) {
                    // Split violations into model / stereotype / structural sections
                    val splitOutput =
                        ValidateJsonOutput(
                            valid = combined.valid,
                            violations =
                                ValidateViolationSplit(
                                    model = modelResult.violations,
                                    stereotype = stereotypeResult?.violations ?: emptyList(),
                                    structural = structuralViolations.map { it.toJsonViolation() },
                                ),
                        )
                    echo(Json { prettyPrint = true }.encodeToString(splitOutput))
                } else if (structuralViolations.isNotEmpty()) {
                    // Emit combined JSON with structural section
                    val splitOutput =
                        ValidateJsonOutput(
                            valid = combined.valid,
                            violations =
                                ValidateViolationSplit(
                                    model = modelResult.violations,
                                    stereotype = emptyList(),
                                    structural = structuralViolations.map { it.toJsonViolation() },
                                ),
                        )
                    echo(Json { prettyPrint = true }.encodeToString(splitOutput))
                } else {
                    echo(Json { prettyPrint = true }.encodeToString(KumlValidationResult.serializer(), modelResult))
                }
            }
            else -> printText(combined, modelResult.violations, stereotypeResult?.violations, structuralViolations)
        }

        if (!combined.valid) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        if (strict && (exprErrors.isNotEmpty() || constraintErrors.isNotEmpty())) {
            throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        }
    }

    /**
     * Collects and attempts to parse all guard/effect expression strings in the
     * extracted diagram (STM guards, ACT ControlFlow guards, state entry/exit/do,
     * transition effects).
     *
     * Returns a list of human-readable error messages for expressions that could
     * not be parsed.  In non-strict mode these are warnings only; in strict mode
     * any non-empty result causes a non-zero exit.
     */
    private fun validateExpressions(extracted: ExtractedDiagram?): List<String> {
        if (extracted == null || extracted !is ExtractedDiagram.Sysml2) return emptyList()
        val model = extracted.model
        val messages = mutableListOf<String>()

        // STM transition guards and effects
        model.usages.filterIsInstance<TransitionUsage>().forEach { tu ->
            tu.guard?.let { guard ->
                val errs = mutableListOf<dev.kuml.expr.ParseError>()
                val parsed = OclLikeExpressionParser.tryParse(guard, errs)
                if (parsed != null) {
                    val type = ExpressionTypeChecker.infer(parsed)
                    if (type is dev.kuml.expr.KumlType.TypeError) {
                        messages +=
                            "transition:${tu.id} guard '$guard': ${type.message}"
                    }
                } else {
                    messages +=
                        "transition:${tu.id} guard '$guard': ${errs.firstOrNull()?.message ?: "parse error"}"
                }
            }
            tu.effect?.let { effect ->
                val errs = mutableListOf<dev.kuml.expr.ParseError>()
                OclLikeExpressionParser.tryParseEffects(effect, errs)
                if (errs.isNotEmpty()) {
                    messages +=
                        "transition:${tu.id} effect '$effect': ${errs.first().message}"
                }
            }
        }

        // ACT ControlFlow guards
        model.usages.filterIsInstance<ControlFlowUsage>().forEach { cf ->
            cf.guard?.let { guard ->
                val errs = mutableListOf<dev.kuml.expr.ParseError>()
                val parsed = OclLikeExpressionParser.tryParse(guard, errs)
                if (parsed == null && errs.isNotEmpty()) {
                    messages +=
                        "controlFlow:${cf.id} guard '$guard': ${errs.first().message}"
                }
            }
        }

        return messages
    }

    /**
     * Runs [Sysml2ConstraintChecker] over all PAR [ConstraintDefinition]s in the
     * extracted diagram.  Returns constraint type errors for display.
     */
    private fun validateConstraints(extracted: ExtractedDiagram?): List<Sysml2ConstraintChecker.ConstraintTypeError> {
        if (extracted == null || extracted !is ExtractedDiagram.Sysml2) return emptyList()
        val model = extracted.model
        // Find all PAR diagrams and check each
        val parDiagrams = model.diagrams.filterIsInstance<ParDiagram>()
        if (parDiagrams.isEmpty()) {
            // No diagram filter — check all constraint definitions in model
            val allConstraints = model.definitions.filterIsInstance<ConstraintDefinition>()
            if (allConstraints.isEmpty()) return emptyList()
            return Sysml2ConstraintChecker.check(model, null)
        }
        return parDiagrams.flatMap { diagram ->
            Sysml2ConstraintChecker.check(model, diagram)
        }
    }

    private fun printText(
        combined: KumlValidationResult,
        modelViolations: List<KumlViolation>,
        stereotypeViolations: List<KumlViolation>?,
        structuralViolations: List<StructuralViolation> = emptyList(),
    ) {
        if (combined.valid && structuralViolations.isEmpty()) {
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
        val totalCount = combined.violations.size + structuralViolations.size
        echo("$totalCount violation(s) found.")
    }

    // ── Structural violation JSON helper ──────────────────────────────────────

    private fun StructuralViolation.toJsonViolation(): StructuralJsonViolation =
        StructuralJsonViolation(
            id = id,
            severity = severity,
            message = message,
            location = location,
            category = category,
        )
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
    @SerialName("structural") val structural: List<StructuralJsonViolation> = emptyList(),
)

/**
 * JSON-serializable representation of a [dev.kuml.cli.validate.StructuralViolation].
 *
 * Carries a [category] field (`"structural"`) so JSON consumers can
 * distinguish structural violations from OCL / stereotype violations
 * (`"ocl"` / `"stereotype"`) by field inspection.
 */
@Serializable
internal data class StructuralJsonViolation(
    val id: String,
    val severity: String,
    val message: String,
    val location: String? = null,
    val category: String = "structural",
)
