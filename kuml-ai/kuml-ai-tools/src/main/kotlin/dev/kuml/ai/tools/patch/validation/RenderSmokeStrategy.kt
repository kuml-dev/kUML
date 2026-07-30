package dev.kuml.ai.tools.patch.validation

import dev.kuml.ai.tools.context.AnyKumlModel

/**
 * Pluggable RENDER-phase strategy for [dev.kuml.ai.tools.patch.PatchValidator].
 *
 * The built-in default ([RenderSmokeCheck]) runs the full
 * `ElkLayoutEngine` → `KumlSvgRenderer` pipeline directly in-process, with no
 * input-size cap, no wall-clock timeout, and no other resource bound. That is
 * an acceptable trade-off for `kuml-desktop`, where the "attacker" model is
 * essentially nonexistent (a single trusted local user renders their own
 * file). It is NOT an acceptable trade-off for a network-facing, multi-tenant
 * service, where an LLM — itself steered by a possibly-adversarial tenant
 * prompt — could otherwise drive the RENDER phase against a pathological
 * model (e.g. very deep nesting, very many elements) with no cap on the work
 * performed on the shared server process.
 *
 * This seam exists so a server-side integration (e.g. kUML Portal) can supply
 * its own [RenderSmokeStrategy] backed by a DoS-hardened renderer — for
 * example one wrapping `PortalRenderer`, which already carries
 * `InterpreterLimits`/`PortalRenderLimits`-style bounds — without requiring
 * ANY further changes to `kuml-ai-tools`. The later integration wave only
 * needs to:
 *
 * ```kotlin
 * val portalValidator = PatchValidator(
 *     renderSmokeEnabled = true,
 *     renderSmokeStrategy = RenderSmokeStrategy { model -> /* delegate to PortalRenderer */ },
 * )
 * val engine = PatchApplyEngine(context = ctx, validator = portalValidator)
 * ```
 *
 * [PatchApplyEngine] already accepts an injected [dev.kuml.ai.tools.patch.PatchValidator]
 * instance, so no change is needed there either — this interface is the only
 * missing seam.
 */
public fun interface RenderSmokeStrategy {
    /**
     * Attempts a render smoke of [model] and returns [PatchValidationResult.Valid]
     * (optionally with warnings) or a [PatchValidationResult.Invalid] with
     * phase [ValidationPhase.RENDER].
     */
    public fun run(model: AnyKumlModel): PatchValidationResult
}
