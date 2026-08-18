package dev.kuml.mcp.tools

import dev.kuml.core.ocl.KumlValidationResult
import dev.kuml.core.ocl.KumlViolation
import dev.kuml.core.ocl.OclValidator
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.FailureKind
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.core.script.ScriptSecurityException
import dev.kuml.core.script.style.NamedArgumentStyleCheck
import dev.kuml.core.script.style.StyleCheckResult
import dev.kuml.core.script.style.StyleFinding
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.constraint.ViolationSeverity
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpScriptEvaluator
import dev.kuml.mcp.McpToolDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal object ValidateTool : McpTool {
    // encodeDefaults = true: ValidateStyleViolation.category is always "style"
    // (this tool's own default), which — with the library's default
    // encodeDefaults = false — would make kotlinx.serialization treat it as
    // "unchanged from schema default" and SILENTLY OMIT it from every
    // response, even though callers (and this tool's own descriptor text)
    // document it as always present. Found via ValidateToolTest.
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    /** Mirrors ValidateCommand's identical constant (kuml-cli) — see its KDoc. */
    private const val STYLE_CHECK_JOIN_TIMEOUT_SECONDS = 20L

    override val descriptor: McpToolDescriptor =
        McpToolDescriptor(
            name = "kuml.validate",
            description =
                "Validate OCL constraints defined in a kUML DSL script (UML classifiers, " +
                    "BPMN processes, or SysML 2 part definitions). Returns a structured list of " +
                    "constraint violations, each with an optional sourcePosition (line/col within " +
                    "the constraint body). Also runs a named-argument source-style check (dev.kuml.* " +
                    "calls with more than one value parameter must use named arguments, per " +
                    "CLAUDE.md's 'Named Parameters — PFLICHT' rule) and returns any findings as a " +
                    "separate styleViolations list; set checkStyle=false to skip it.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("script") {
                            put("type", "string")
                            put("description", "kUML DSL script content with constraint() declarations.")
                        }
                        putJsonObject("checkStyle") {
                            put("type", "boolean")
                            put(
                                "description",
                                "Run the named-argument source-style check against the script's own " +
                                    "source text. Default: true.",
                            )
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("script")) }
                },
        )

    override fun call(arguments: JsonObject): List<McpContent> {
        val script =
            arguments["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing required argument: script")
        val checkStyle = arguments["checkStyle"]?.jsonPrimitive?.boolean ?: true

        // Kick off the style check concurrently with script evaluation below —
        // the two are fully independent (the style check never evaluates the
        // script, only statically resolves symbols in its source text), so this
        // keeps the added wall-clock cost close to zero. Mirrors ValidateCommand
        // (kuml-cli)'s identical step 0.
        val styleCheckTask: FutureTask<StyleCheckResult>? =
            if (!checkStyle) {
                null
            } else {
                FutureTask { NamedArgumentStyleCheck.check(source = script, fileName = "validate.kuml.kts") }.also {
                    Thread(it, "kuml-mcp-validate-style-check").apply { isDaemon = true }.start()
                }
            }

        // V0.23.3 — evaluation + extraction run through the sandboxed evaluator.
        // Dispatch mirrors ValidateCommand: UML validates via OclValidator.validate,
        // BPMN / SysML 2 route to their dedicated validators. ERM (V3.4.1) has no
        // OCL constraints — it routes to ErmConstraintChecker instead (structural
        // rules, not OCL invariants), mapped into the same KumlViolation shape so
        // this tool's response contract doesn't need a second result type. Any
        // other diagram kind (C4, Blueprint) has no constraint concept at all and
        // validates trivially.
        //
        // Uses the non-throwing McpScriptEvaluator.evaluate (not .extract) so the
        // style check's outcome — collected below, unconditionally — is available
        // even when script evaluation itself fails; the failure is then re-thrown
        // (preserving the existing GUARD → ScriptSecurityException / other →
        // ScriptEvaluationException routing that McpServer's top-level handler
        // depends on) with the style findings folded into the message so they are
        // not silently lost.
        val evaluated = McpScriptEvaluator.evaluate(script = script, fileName = "validate.kuml.kts")
        val styleViolations = collectStyleViolations(styleCheckTask)

        if (evaluated is EvaluatedScript.Failure) {
            val suffix =
                if (styleViolations.isEmpty()) {
                    ""
                } else {
                    "\n\nAdditionally, the source-style check found ${styleViolations.size} finding(s):\n" +
                        styleViolations.joinToString("\n") { "  [${it.id}] ${it.location ?: ""}: ${it.message}" }
                }
            when (evaluated.kind) {
                FailureKind.GUARD -> throw ScriptSecurityException(evaluated.message + suffix)
                else -> throw ScriptEvaluationException(message = evaluated.message + suffix)
            }
        }

        val extracted = (evaluated as EvaluatedScript.Success).diagram
        val result = validationResultFor(extracted)

        val response =
            ValidateToolResponse(
                valid = result.valid && styleViolations.none { it.severity == "error" },
                violations = result.violations,
                styleViolations = styleViolations,
            )
        val resultJson = json.encodeToString(ValidateToolResponse.serializer(), response)
        return listOf(McpContent(type = "text", text = resultJson))
    }

    private fun validationResultFor(extracted: ExtractedDiagram): KumlValidationResult =
        when (extracted) {
            is ExtractedDiagram.Uml -> OclValidator.validate(extracted.diagram)
            is ExtractedDiagram.Bpmn -> OclValidator.validateBpmn(extracted.model)
            is ExtractedDiagram.Sysml2 -> OclValidator.validateSysml2(extracted.model)
            is ExtractedDiagram.Erm -> {
                val model = extracted.model
                val checks = ErmConstraintChecker().check(model)
                val violations =
                    checks.map { v ->
                        val elementName =
                            v.elementId?.let { id -> model.elementById(id)?.name } ?: model.name
                        KumlViolation(
                            constraintId = v.elementId ?: model.name,
                            constraintName = if (v.severity == ViolationSeverity.WARNING) "erm-warning" else "erm-error",
                            classifierId = v.elementId ?: model.name,
                            classifierName = elementName ?: (v.elementId ?: model.name),
                            oclExpression = "",
                            message = v.message,
                        )
                    }
                val hasErrors = checks.any { it.severity == ViolationSeverity.ERROR }
                KumlValidationResult(valid = !hasErrors, violations = violations)
            }
            else -> KumlValidationResult(valid = true, violations = emptyList())
        }

    /**
     * Waits for the background [styleCheckTask] (started in [call]) and
     * converts its [StyleCheckResult] into [ValidateStyleViolation]s. Mirrors
     * `ValidateCommand.collectStyleViolations` (kuml-cli) — see its KDoc for
     * the ERROR-vs-WARNING rationale.
     */
    private fun collectStyleViolations(styleCheckTask: FutureTask<StyleCheckResult>?): List<ValidateStyleViolation> {
        if (styleCheckTask == null) return emptyList()
        val result =
            try {
                styleCheckTask.get(STYLE_CHECK_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                StyleCheckResult.Unavailable("Style check did not complete: ${e::class.simpleName}")
            }
        return when (result) {
            is StyleCheckResult.Ok -> result.findings.map { it.toValidateStyleViolation() }
            is StyleCheckResult.Unavailable ->
                listOf(
                    ValidateStyleViolation(
                        id = "STYLE_CHECK_UNAVAILABLE",
                        severity = "warning",
                        message = result.reason,
                        line = 0,
                        column = 0,
                        location = null,
                    ),
                )
        }
    }

    private fun StyleFinding.toValidateStyleViolation(): ValidateStyleViolation =
        ValidateStyleViolation(
            id = id,
            severity = severity,
            message = message,
            line = line,
            column = column,
            location = location,
        )
}

/**
 * `kuml.validate`'s response envelope. Additive over the pre-existing
 * `{valid, violations}` shape (see `KumlValidationResult`) — `styleViolations`
 * is a new field, `valid`/`violations` keep their exact paths and semantics,
 * so existing consumers that only read those two fields are unaffected; only
 * `valid` can newly become `false` because of a style finding.
 */
@Serializable
internal data class ValidateToolResponse(
    val valid: Boolean,
    val violations: List<KumlViolation>,
    val styleViolations: List<ValidateStyleViolation> = emptyList(),
)

@Serializable
internal data class ValidateStyleViolation(
    val id: String,
    val severity: String,
    val message: String,
    val line: Int,
    val column: Int,
    val location: String? = null,
    val category: String = "style",
)
