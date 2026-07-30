package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.DeepCopy
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.validation.PatchValidationResult
import dev.kuml.ai.tools.patch.validation.RenderSmokeCheck
import dev.kuml.ai.tools.patch.validation.RenderSmokeStrategy
import dev.kuml.ai.tools.patch.validation.StructuralPatchChecks
import dev.kuml.ai.tools.patch.validation.TypeCheckPatchChecks
import dev.kuml.ai.tools.patch.validation.ValidationError
import dev.kuml.ai.tools.patch.validation.ValidationPhase
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.SandboxValidator
import dev.kuml.uml.UmlStateMachine

/**
 * Validates a single [ModelPatch] against a fresh clone of the base model,
 * without ever touching the caller's working state. Used by [PatchApplyEngine]
 * before each `applyOne()` and exposed standalone for V3.0.24 "preview-validate"
 * UI affordances.
 *
 * ## Validation pipeline (in order):
 *   1. **STRUCTURAL** — clone + apply + duplicate-id / dangling-ref / cycle checks
 *   2. **SANDBOX**    — UmlStateMachine sandbox checks (skipped for non-STM patches)
 *   3. **TYPE_CHECK** — expression-AST type validation for guard/effect patches
 *   4. **RENDER**     — (optional, off-by-default) tries to layout + render
 *
 * Phases 1–3 are FAIL-FAST per phase boundary: each phase collects all errors
 * within itself before deciding fail/pass. A failing phase short-circuits later ones.
 *
 * The RENDER phase is pluggable via [renderSmokeStrategy], which has **no unsafe
 * default**: passing [renderSmokeEnabled] = true without also supplying an explicit
 * [renderSmokeStrategy] fails fast at construction time (see the `init` block below)
 * rather than silently falling back to the unbounded, no-size-cap, no-timeout
 * [RenderSmokeCheck] renderer. That renderer is only appropriate for a trusted,
 * single-user desktop/CLI caller — use [PatchValidator.desktop] to opt into it
 * explicitly by name. A server-side caller must inject its own DoS-hardened
 * [RenderSmokeStrategy] before enabling [renderSmokeEnabled] against
 * untrusted/multi-tenant input — see [RenderSmokeStrategy]'s KDoc.
 */
public class PatchValidator(
    private val sandboxPolicy: SandboxPolicy = SandboxPolicy.Strict,
    private val renderSmokeEnabled: Boolean = false,
    private val renderSmokeStrategy: RenderSmokeStrategy? = null,
) {
    init {
        require(!renderSmokeEnabled || renderSmokeStrategy != null) {
            "PatchValidator(renderSmokeEnabled = true) requires an explicit renderSmokeStrategy. " +
                "The unbounded desktop-only default (RenderSmokeCheck: no size cap, no timeout) is " +
                "intentionally NOT wired in implicitly, to avoid a silent multi-tenant DoS trap for " +
                "future callers. Use PatchValidator.desktop(...) for a trusted single-user caller, " +
                "or pass a DoS-hardened RenderSmokeStrategy for server-side/multi-tenant use — see " +
                "RenderSmokeStrategy's KDoc."
        }
    }

    public companion object {
        /**
         * Convenience factory for trusted, single-user callers (desktop app, CLI, local
         * tooling) that want the RENDER smoke phase enabled with the default, unbounded
         * [RenderSmokeCheck] strategy (no size cap, no timeout).
         *
         * Do NOT use this for a network-facing, multi-tenant service — construct
         * [PatchValidator] directly with a DoS-hardened [RenderSmokeStrategy] instead.
         */
        public fun desktop(sandboxPolicy: SandboxPolicy = SandboxPolicy.Strict): PatchValidator =
            PatchValidator(
                sandboxPolicy = sandboxPolicy,
                renderSmokeEnabled = true,
                renderSmokeStrategy = RenderSmokeStrategy { model -> RenderSmokeCheck.run(model) },
            )
    }

    /**
     * Validates [patch] by applying it to a clone of [baseModel].
     *
     * The [mutate] function must be the same function that would be passed to
     * [dev.kuml.ai.tools.context.AgentEditingContext.applyPatch] — i.e. the
     * model-specific transformation. Use [dev.kuml.ai.tools.patch.apply.ModelMutationRouter.mutateFor]
     * to obtain the function from a patch.
     *
     * @return [PatchValidationResult.Valid] if all enabled phases pass,
     *         [PatchValidationResult.Invalid] with the failing phase and errors otherwise.
     */
    public fun validate(
        baseModel: AnyKumlModel,
        patch: ModelPatch,
        mutate: (AnyKumlModel) -> AnyKumlModel,
    ): PatchValidationResult {
        val warnings = mutableListOf<String>()

        // ── Phase 1: STRUCTURAL (includes apply-on-clone) ──────────────────────
        val clone = DeepCopy.copy(baseModel)
        val patched =
            try {
                mutate(clone)
            } catch (e: Exception) {
                return PatchValidationResult.Invalid(
                    errors =
                        listOf(
                            ValidationError(
                                code = "APPLY_FAILED",
                                message = "Patch apply on clone threw: ${e.message ?: e.javaClass.simpleName}",
                            ),
                        ),
                    phase = ValidationPhase.STRUCTURAL,
                )
            }

        val structuralErrors = StructuralPatchChecks.run(model = patched, warnings = warnings)
        if (structuralErrors.isNotEmpty()) {
            return PatchValidationResult.Invalid(errors = structuralErrors, phase = ValidationPhase.STRUCTURAL)
        }

        // ── Phase 2: SANDBOX (only for STM patches) ────────────────────────────
        val stm = extractStateMachine(model = patched, diagramId = patch.diagramId)
        if (stm != null && isSandboxRelevant(patch)) {
            val report = SandboxValidator(sandboxPolicy).validate(stm)
            if (!report.isClean) {
                val errors =
                    report.violations.map { v ->
                        ValidationError(
                            code = v.kind.name,
                            message = v.message,
                            locationHint = v.location.vertexId ?: v.location.transitionId,
                        )
                    }
                return PatchValidationResult.Invalid(errors = errors, phase = ValidationPhase.SANDBOX)
            }
        }

        // ── Phase 3: TYPE_CHECK (only for expression-carrying patches) ──────────
        val typeErrors = TypeCheckPatchChecks.run(patch = patch, policy = sandboxPolicy, warnings = warnings)
        if (typeErrors.isNotEmpty()) {
            return PatchValidationResult.Invalid(errors = typeErrors, phase = ValidationPhase.TYPE_CHECK)
        }

        // ── Phase 4: RENDER (opt-in, pluggable — see [RenderSmokeStrategy]) ─────
        if (renderSmokeEnabled) {
            // Non-null is guaranteed by the init{} check above (renderSmokeEnabled = true
            // requires a non-null renderSmokeStrategy at construction time).
            val strategy = checkNotNull(renderSmokeStrategy) { "renderSmokeStrategy must be non-null when renderSmokeEnabled" }
            val renderResult = strategy.run(patched)
            if (renderResult is PatchValidationResult.Invalid) return renderResult
            if (renderResult is PatchValidationResult.Valid) {
                warnings.addAll(renderResult.warnings)
            }
        }

        return PatchValidationResult.Valid(warnings = warnings)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if this patch targets fields that the sandbox validator checks
     * (i.e. state-machine action bodies or guards).
     */
    private fun isSandboxRelevant(patch: ModelPatch): Boolean =
        when (patch) {
            is ModelPatch.UpdateAttribute ->
                patch.field in setOf("guard", "effect", "entry", "exit", "doActivity")
            is ModelPatch.AddElement ->
                patch.elementKind.contains("state", ignoreCase = true) ||
                    patch.elementKind.contains("transition", ignoreCase = true)
            else -> false
        }

    /**
     * Extracts the first [UmlStateMachine] from [model].
     * Returns null for non-UML models or when no STM is present.
     * Per Plan §4.4: if no STM is found, the SANDBOX phase is a no-op.
     */
    private fun extractStateMachine(
        model: AnyKumlModel,
        diagramId: String?,
    ): UmlStateMachine? =
        when (model) {
            is AnyKumlModel.Uml ->
                model.elements
                    .filterIsInstance<UmlStateMachine>()
                    .firstOrNull { diagramId == null || it.id == diagramId }
                    ?: model.elements.filterIsInstance<UmlStateMachine>().firstOrNull()
            is AnyKumlModel.Sysml2 -> null // SysML 2 STM adapter not yet wired
            is AnyKumlModel.C4 -> null // C4 has no state machines
        }
}
