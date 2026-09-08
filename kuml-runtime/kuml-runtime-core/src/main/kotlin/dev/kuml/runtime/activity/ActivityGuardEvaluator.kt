package dev.kuml.runtime.activity

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance

/**
 * Default [GuardEvaluator] for [ActivityRuntime] token-flow guards (ADR-0015 /
 * security fix B2).
 *
 * Extracted from the (previously dead) `ActivityRuntime.evaluateGuard` env-building
 * logic so it can be shared with `kuml-runtime-tokenflow`'s `TokenFlowGuardEvaluator`
 * and — critically — so it can be wrapped by
 * `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator` by callers that need a
 * bounded guard-evaluation time budget (`kuml simulate --sandbox`, `kuml run`,
 * the MCP `kuml.run.*` tools).
 *
 * Before this fix, [ActivityRuntime] declared a `guardEvaluator` constructor
 * parameter but never actually invoked it — `evaluateGuard` called
 * `OclExpressions.evaluate` directly, so a `TimeLimitedGuardEvaluator` injected by
 * a caller had no effect and a malicious/buggy guard expression could hang
 * activity execution indefinitely. This class restores the exact historical
 * evaluation semantics (bare-identifier guards like `"allow"` / `"!allow"`
 * resolve directly against the event context) while making the evaluator
 * actually pluggable.
 *
 * ## Evaluation environment
 *
 * Bare-identifier guards must keep working (`ActivityRuntimeTest`,
 * `Sysml2ActivityAdapterTest`, and the BPMN OKF examples all rely on this), so
 * the [instance]'s `variables` map is merged directly into the OCL evaluation
 * env at the top level, and is also exposed under the conventional `"event"`
 * and `"vars"` keys for guards written in the `event.foo` / `vars.foo` style
 * used by the STM side.
 */
public class ActivityGuardEvaluator : GuardEvaluator {
    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult {
        if (guard.isNullOrBlank()) return GuardResult.True

        val cleaned =
            guard.trim().let {
                if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1).trim() else it
            }

        return try {
            val context: Map<String, Any?> = instance.variables
            val env: Map<String, Any?> =
                context +
                    mapOf(
                        "event" to context,
                        "vars" to context,
                    )
            val raw = OclExpressions.evaluate(expression = cleaned, self = instance, env = env)
            when (raw) {
                true -> GuardResult.True
                false -> GuardResult.False
                null -> GuardResult.False
                else -> GuardResult.Failed("Guard did not evaluate to Boolean (got $raw)")
            }
        } catch (ex: OclEvaluationException) {
            GuardResult.False
        } catch (ex: Exception) {
            GuardResult.False
        }
    }
}
