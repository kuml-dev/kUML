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
import dev.kuml.core.script.style.NamedArgumentStyleCheck
import dev.kuml.core.script.style.StyleCheckResult
import dev.kuml.core.script.style.StyleFinding
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.constraint.ViolationSeverity
import dev.kuml.expr.ExpressionTypeChecker
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.profile.ProfileRegistry
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.constraint.ReqUcSeverity
import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2ConstraintChecker
import dev.kuml.sysml2.constraint.Sysml2ReqUcChecker
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

// Single shared pretty-printing Json instance — creating a new Json {} per call
// is flagged by the kotlinx.serialization compiler plugin as needlessly slow.
private val kumlPrettyJson = Json { prettyPrint = true }

// Outer join timeout for the background style-check task. Generous headroom
// above NamedArgumentStyleCheck's own internal 10s child-process timeout —
// this only guards against the join() call itself somehow never returning
// (e.g. a JVM-level scheduling pathology), not the normal timeout path,
// which NamedArgumentStyleCheck already handles by returning Unavailable.
private const val STYLE_CHECK_JOIN_TIMEOUT_SECONDS = 20L

/**
 * The `validate` subcommand.
 *
 * Evaluates a `*.kuml.kts` script and checks all OCL constraints.
 * When [checkStereotypes] is `true`, additionally runs [StereotypeValidator].
 *
 * Exit codes:
 * - 0: no violations
 * - 3: script error
 * - 5: violations found (see [ExitCodes.VALIDATION_VIOLATIONS])
 */
internal class ValidateCommand : CliktCommand(name = "validate") {
    private val input by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    // "--format" is accepted as an alias for "--output" (V3.2.23) — some wave
    // plans/docs referred to the flag as "--format json" before the CLI's
    // actual flag name ("-o/--output") was cross-checked; keeping both spellings
    // working avoids breaking any script written against the documented name.
    private val outputFormat by option("-o", "--output", "--format", help = "Output format")
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

    private val noCheckStyle by option(
        "--no-check-style",
        help =
            "Skip the named-argument source-style check (dev.kuml.* calls with more than one " +
                "value parameter must use named arguments). Default: style checks are ON.",
    ).flag(default = false)

    override fun help(context: Context): String = "Validate OCL constraints in a kUML script."

    override fun run() {
        // 0. Kick off the source-style check (RequireNamedArguments, ported to run
        //    against real source text — see :kuml-style-worker) on a background
        //    thread, concurrently with step 1's script evaluation below. The two
        //    are fully independent (the style check never evaluates the script,
        //    only statically resolves symbols in it), so running them in parallel
        //    keeps the added wall-clock cost close to zero against the ~1.2s the
        //    style check's own child-process worker takes.
        val styleCheckTask: FutureTask<StyleCheckResult>? =
            if (noCheckStyle) {
                null
            } else {
                val source = input.readText()
                FutureTask { NamedArgumentStyleCheck.check(source = source, fileName = input.name) }.also {
                    Thread(it, "kuml-validate-style-check").apply { isDaemon = true }.start()
                }
            }

        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(file = input)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }

        // 1b. Collect the style check's outcome now — independent of whether the
        //     script itself evaluated successfully (a script with a syntax error
        //     may still have named-argument violations worth reporting, and the
        //     style check tolerates partially-broken input; see NamedArgumentStyleCheck).
        val styleViolations: List<StructuralViolation> = collectStyleViolations(styleCheckTask)
        val styleErrors = styleViolations.filter { it.severity == "error" }
        val styleWarnings = styleViolations.filter { it.severity == "warning" }

        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            if (styleViolations.isNotEmpty()) {
                echo("\nStyle validation:")
                for (sv in styleErrors) echo("  ERROR [${sv.id}] ${sv.location}: ${sv.message}")
                for (sv in styleWarnings) echo("  WARN  [${sv.id}] ${sv.message}")
            }
            // Same findings (with line and column) the desktop app shows: one service, one wording.
            val scriptFindings =
                ValidationService
                    .validateEvaluated(result = evalResult, source = input.readText(), fileName = input.name)
                    .filter { it.severity == Severity.ERROR }
            val lines =
                scriptFindings.map { it.format() }.ifEmpty { errors.map { it.message } }
            echo("Script error: ${lines.joinToString("\n")}", err = true)
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
                DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = input)
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
                    DiagramExtractor.extract(returnValue = success.value.returnValue, input = input)
                } catch (_: Throwable) {
                    null
                }
            }

        // 3. Model OCL validation.
        //    - UML: OclValidator.validate over classifier constraints (unchanged).
        //    - BPMN / SysML 2 (V3.2.23): OclValidator.validateBpmn / validateSysml2
        //      evaluate process-/part-level OCL invariants. Any other extracted
        //      diagram kind (C4, Blueprint) has no OCL constraint concept and
        //      validates trivially (valid = true, no violations).
        val modelResult =
            when {
                umlDiagram != null -> OclValidator.validate(umlDiagram)
                extracted is ExtractedDiagram.Bpmn -> OclValidator.validateBpmn(extracted.model)
                extracted is ExtractedDiagram.Sysml2 -> OclValidator.validateSysml2(extracted.model)
                else -> KumlValidationResult(valid = true, violations = emptyList())
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

        // 7b. ERM validation (V3.4.1) — ErmConstraintChecker's structural rules, not OCL.
        //     ERM has no OCL constraint concept, so this runs independently of step 3's
        //     modelResult (which stays `valid = true, violations = emptyList()` for ERM).
        //     Reuses the StructuralViolation shape (category = "erm") so the JSON output
        //     doesn't need a new top-level field, and folds into the same `structural`
        //     JSON section as UML's structural checks.
        val ermViolations: List<StructuralViolation> =
            (extracted as? ExtractedDiagram.Erm)?.let { erm ->
                ErmConstraintChecker().check(erm.model).map { v ->
                    StructuralViolation(
                        id = v.elementId ?: erm.model.name,
                        severity = if (v.severity == ViolationSeverity.ERROR) "error" else "warning",
                        message = v.message,
                        location = v.elementId,
                        category = "erm",
                    )
                }
            } ?: emptyList()
        val ermErrors = ermViolations.filter { it.severity == "error" }
        val ermWarnings = ermViolations.filter { it.severity == "warning" }

        if (ermViolations.isNotEmpty()) {
            echo("\nERM validation:")
            for (ev in ermErrors) {
                echo("  ERROR [${ev.id}] ${ev.message}")
            }
            for (ev in ermWarnings) {
                echo("  WARN  [${ev.id}] ${ev.message}")
            }
        }

        // 7b'. Note validation — structural note findings plus NOTE_TRUNCATED display-limit warnings
        //      for SysML 2 / BPMN / ERM / UML notes. Same StructuralViolation shape, category "notes".
        val noteViolations: List<StructuralViolation> =
            extracted?.let { ex ->
                ValidationService
                    .findingsOf(ex)
                    // SysML 2 layout blocks are range/id-checked by Sysml2LayoutHintChecker below; only the matrix finding is new.
                    .filterNot {
                        ex is ExtractedDiagram.Sysml2 &&
                            (
                                it.code == dev.kuml.core.model.layout.LayoutCodes.UNKNOWN_ID ||
                                    it.code == dev.kuml.core.model.layout.LayoutCodes.OUT_OF_RANGE
                            )
                    }.map { f ->
                        StructuralViolation(
                            id = f.code,
                            severity = if (f.severity == Severity.ERROR) "error" else "warning",
                            message = f.format().removePrefix("[${f.code}] "),
                            location = f.anchorId ?: f.diagramId,
                            category = if (f.code.startsWith("KUML-LAYOUT")) "layout" else "notes",
                        )
                    }
            } ?: emptyList()
        val noteErrors = noteViolations.filter { it.severity == "error" }
        val noteWarnings = noteViolations.filter { it.severity == "warning" }
        if (noteViolations.isNotEmpty()) {
            echo("\nNote validation:")
            for (nv in noteErrors) echo("  ERROR [${nv.id}] ${nv.message}")
            for (nv in noteWarnings) echo("  WARN  [${nv.id}] ${nv.message}")
        }

        // 7b''. STM structure validation (wave A1: composite states, regions, pseudo-states, history).
        val stmViolations: List<StructuralViolation> =
            (extracted as? ExtractedDiagram.Sysml2)
                ?.takeIf { it.diagram is StmDiagram }
                ?.let { ex ->
                    Sysml2StmChecker.check(model = ex.model, diagram = ex.diagram as StmDiagram).map { i ->
                        StructuralViolation(
                            id = "STM${i.code}",
                            severity = if (i.severity == StmSeverity.Error) "error" else "warning",
                            message = "${i.message} Hint: ${i.hint}",
                            location = i.elementId,
                            category = "stm",
                        )
                    }
                } ?: emptyList()
        val stmErrors = stmViolations.filter { it.severity == "error" }
        val stmWarnings = stmViolations.filter { it.severity == "warning" }
        if (stmViolations.isNotEmpty()) {
            echo("\nState machine validation:")
            for (sv in stmErrors) echo("  ERROR [${sv.id}] ${sv.location}: ${sv.message}")
            for (sv in stmWarnings) echo("  WARN  [${sv.id}] ${sv.location}: ${sv.message}")
        }

        // 7b'''. REQ / UC structure validation (wave C1: copy/subject/group/generalisation rules and limits).
        val reqUcViolations: List<StructuralViolation> =
            (extracted as? ExtractedDiagram.Sysml2)
                ?.takeIf {
                    it.diagram is ReqDiagram ||
                        it.diagram is UcDiagram ||
                        it.diagram is dev.kuml.sysml2.ActDiagram ||
                        it.diagram is dev.kuml.sysml2.SeqDiagram
                }?.let { ex ->
                    Sysml2ReqUcChecker.check(model = ex.model, diagram = ex.diagram).map { i ->
                        StructuralViolation(
                            id = "REQUC${i.code}",
                            severity = if (i.severity == ReqUcSeverity.Error) "error" else "warning",
                            message = "${i.message} Hint: ${i.hint}",
                            location = i.elementId,
                            category = "reqUc",
                        )
                    } +
                        // Wave C2: activity diagram checks (codes 301-315) share this report section.
                        dev.kuml.sysml2.constraint.Sysml2ActChecker.check(model = ex.model, diagram = ex.diagram).map { i ->
                            StructuralViolation(
                                id = "ACT${i.code}",
                                severity = if (i.severity == dev.kuml.sysml2.constraint.ActSeverity.Error) "error" else "warning",
                                message = "${i.message} Hint: ${i.hint}",
                                location = i.elementId,
                                category = "act",
                            )
                        } +
                        // Wave C3: sequence diagram checks (codes 401-412).
                        dev.kuml.sysml2.constraint.Sysml2SeqChecker.check(model = ex.model, diagram = ex.diagram).map { i ->
                            StructuralViolation(
                                id = "SEQ${i.code}",
                                severity = if (i.severity == dev.kuml.sysml2.constraint.SeqSeverity.Error) "error" else "warning",
                                message = "${i.message} Hint: ${i.hint}",
                                location = i.elementId,
                                category = "seq",
                            )
                        }
                } ?: emptyList()
        // Wave C4: parametric structure checks (codes 437-441): nested constraints and pin bindings.
        val parViolations: List<StructuralViolation> =
            (extracted as? ExtractedDiagram.Sysml2)
                ?.let { ex ->
                    (ex.diagram as? dev.kuml.sysml2.ParDiagram)?.let { pd ->
                        dev.kuml.sysml2.constraint.Sysml2ParChecker.check(model = ex.model, diagram = pd).map { i ->
                            StructuralViolation(
                                id = "PAR${i.code}",
                                severity = if (i.severity == dev.kuml.sysml2.constraint.ParSeverity.Error) "error" else "warning",
                                message = "${i.message} Hint: ${i.hint}",
                                location = i.elementId,
                                category = "par",
                            )
                        }
                    }
                } ?: emptyList()
        // Wave C3: layout hint checks (412, 431-436) for every SysML 2 diagram type.
        val layoutHintViolations: List<StructuralViolation> =
            (extracted as? ExtractedDiagram.Sysml2)
                ?.let { ex ->
                    dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker.check(model = ex.model, diagram = ex.diagram).map { i ->
                        StructuralViolation(
                            id = "LAYOUT${i.code}",
                            severity = if (i.severity == dev.kuml.sysml2.constraint.LayoutSeverity.Error) "error" else "warning",
                            message = "${i.message} Hint: ${i.hint}",
                            location = i.elementId,
                            category = "layout",
                        )
                    }
                } ?: emptyList()
        val reqUcErrors = (reqUcViolations + layoutHintViolations + parViolations).filter { it.severity == "error" }
        val reqUcWarnings = (reqUcViolations + layoutHintViolations + parViolations).filter { it.severity == "warning" }
        if (reqUcViolations.isNotEmpty() || layoutHintViolations.isNotEmpty() || parViolations.isNotEmpty()) {
            echo("\nRequirement / use case validation:")
            for (rv in reqUcErrors) echo("  ERROR [${rv.id}] ${rv.location}: ${rv.message}")
            for (rv in reqUcWarnings) echo("  WARN  [${rv.id}] ${rv.location}: ${rv.message}")
        }

        // 7c. Style validation output (V0.50.0) — see step 0/1b above for where
        //     styleViolations/styleErrors/styleWarnings were computed. Kept as its
        //     own section (not folded into "Structural validation:") so the
        //     source-style nature of these findings stays visually distinct, even
        //     though it flows into the same allStructuralLikeViolations /
        //     `category: "style"` JSON section as structural/ERM findings below.
        if (styleViolations.isNotEmpty()) {
            echo("\nStyle validation:")
            for (sv in styleErrors) {
                echo("  ERROR [${sv.id}] ${sv.location}: ${sv.message}")
            }
            for (sv in styleWarnings) {
                echo("  WARN  [${sv.id}] ${sv.message}")
            }
        }

        // 8. Output
        val allViolations =
            modelResult.violations + (stereotypeResult?.violations ?: emptyList())
        val allStructuralLikeViolations =
            structuralViolations +
                ermViolations +
                noteViolations +
                stmViolations +
                reqUcViolations +
                layoutHintViolations +
                parViolations +
                styleViolations
        val combined =
            KumlValidationResult(
                valid =
                    allViolations.isEmpty() &&
                        structuralErrors.isEmpty() &&
                        ermErrors.isEmpty() &&
                        noteErrors.isEmpty() &&
                        stmErrors.isEmpty() &&
                        reqUcErrors.isEmpty() &&
                        styleErrors.isEmpty(),
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
                                    structural = allStructuralLikeViolations.map { it.toJsonViolation() },
                                ),
                        )
                    echo(kumlPrettyJson.encodeToString(splitOutput))
                } else if (allStructuralLikeViolations.isNotEmpty()) {
                    // Emit combined JSON with structural section
                    val splitOutput =
                        ValidateJsonOutput(
                            valid = combined.valid,
                            violations =
                                ValidateViolationSplit(
                                    model = modelResult.violations,
                                    stereotype = emptyList(),
                                    structural = allStructuralLikeViolations.map { it.toJsonViolation() },
                                ),
                        )
                    echo(kumlPrettyJson.encodeToString(splitOutput))
                } else {
                    echo(kumlPrettyJson.encodeToString(KumlValidationResult.serializer(), modelResult))
                }
            }
            else ->
                printText(
                    combined = combined,
                    modelViolations = modelResult.violations,
                    stereotypeViolations = stereotypeResult?.violations,
                    structuralViolations = allStructuralLikeViolations,
                )
        }

        if (!combined.valid) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        if (strict && (exprErrors.isNotEmpty() || constraintErrors.isNotEmpty() || stmWarnings.isNotEmpty())) {
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

        // STM transition guards. Prose guards are free text and never parsed; transition effects are
        // action descriptions (the simulator only logs them), so they are not parsed either.
        model.usages.filterIsInstance<TransitionUsage>().forEach { tu ->
            if (tu.guardKind == dev.kuml.sysml2.GuardKind.Prose) return@forEach
            tu.guard?.let { guard ->
                val errs = mutableListOf<dev.kuml.expr.ParseError>()
                val parsed = OclLikeExpressionParser.tryParse(input = guard, errors = errs)
                if (parsed != null) {
                    val type = ExpressionTypeChecker.infer(expr = parsed)
                    if (type is dev.kuml.expr.KumlType.TypeError) {
                        messages +=
                            "transition:${tu.id} guard '$guard': ${type.message}"
                    }
                } else {
                    messages +=
                        "transition:${tu.id} guard '$guard': ${errs.firstOrNull()?.message ?: "parse error"}" +
                        " (free text? use guard = prose(\"...\"))"
                }
            }
        }

        // ACT ControlFlow guards
        model.usages.filterIsInstance<ControlFlowUsage>().forEach { cf ->
            cf.guard?.let { guard ->
                val errs = mutableListOf<dev.kuml.expr.ParseError>()
                val parsed = OclLikeExpressionParser.tryParse(input = guard, errors = errs)
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
            return Sysml2ConstraintChecker.check(model = model, diagram = null)
        }
        return parDiagrams.flatMap { diagram ->
            Sysml2ConstraintChecker.check(model = model, diagram = diagram)
        }
    }

    /**
     * Waits for the background [styleCheckTask] (started in step 0 of [run])
     * and converts its [StyleCheckResult] into [StructuralViolation]s.
     *
     * [StyleCheckResult.Ok] findings become `severity = "error"` violations
     * with `id = "POSITIONAL_ARGUMENT"`. [StyleCheckResult.Unavailable] — the
     * worker library was missing, the child process failed to start, or it
     * timed out — becomes a single `severity = "warning"` violation with
     * `id = "STYLE_CHECK_UNAVAILABLE"`: deliberately a WARNING, never an
     * ERROR, because a broken installation is not a defect in the user's
     * script (see `NamedArgumentStyleCheck`'s KDoc / CLAUDE.md task plan §5).
     * `styleCheckTask == null` (i.e. `--no-check-style`) yields an empty list.
     */
    private fun collectStyleViolations(styleCheckTask: FutureTask<StyleCheckResult>?): List<StructuralViolation> {
        if (styleCheckTask == null) return emptyList()
        val result =
            try {
                styleCheckTask.get(STYLE_CHECK_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                StyleCheckResult.Unavailable("Style check did not complete: ${e::class.simpleName}")
            }
        return when (result) {
            is StyleCheckResult.Ok -> result.findings.map { it.toStructuralViolation() }
            is StyleCheckResult.Unavailable ->
                listOf(
                    StructuralViolation(
                        id = "STYLE_CHECK_UNAVAILABLE",
                        severity = "warning",
                        message = result.reason,
                        location = null,
                        category = "style",
                    ),
                )
        }
    }

    private fun StyleFinding.toStructuralViolation(): StructuralViolation =
        StructuralViolation(
            id = id,
            severity = severity,
            message = message,
            location = location,
            category = "style",
        )

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
