package dev.kuml.runtime.tokenflow

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.activity.ActivityGuardEvaluator

/**
 * Default [GuardEvaluator] for [TokenFlowEngine] gateway guards.
 *
 * Delegates to [ActivityGuardEvaluator] rather than duplicating its
 * env-building logic: both engines need the exact same "bare identifiers
 * resolve directly" behaviour (`condition = "verfuegbar"` in the BPMN OKF
 * examples, `guard = "allow"` in `ActivityRuntimeTest`) because
 * `dev.kuml.runtime.OclGuardEvaluator`'s default `{event, vars}`-only env
 * would silently fail every such guard and choose the wrong branch — and the
 * same guard dialect (`!`, `!=`, `&&`, `||` via the typed AST front-end,
 * `not`/`and`/`or`/`<>` via the OCL front-end), so a negated guard chooses
 * the same branch on this engine as it does on [ActivityRuntime].
 *
 * Never constructed bare by [TokenFlowEngine.sandboxed] — it is always wrapped
 * in `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator` (ADR-0015 doctrine:
 * token-flow guard evaluation is unconditionally sandboxed, unlike the legacy
 * `--sandbox` opt-in flag on the STM path).
 */
public class TokenFlowGuardEvaluator(
    private val delegate: GuardEvaluator = ActivityGuardEvaluator(),
) : GuardEvaluator {
    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult = delegate.evaluate(guard = guard, instance = instance, event = event)
}

/**
 * Minimal [ModelInstance] shim exposing a [TokenFlowContext]'s variables to
 * guard evaluation — analogous to `ActivityRuntime`'s private
 * `ActivityEvalContext`, but public within the module since
 * [TokenFlowEngine] and its tests both need it.
 */
internal class TokenFlowEvalContext(
    context: Map<String, Any?>,
) : ModelInstance<TokenFlowSpec> {
    override val model: TokenFlowSpec get() = error("TokenFlowEvalContext.model should not be accessed")
    override val currentVertices: List<dev.kuml.uml.UmlVertex> = emptyList()
    override val variables: MutableMap<String, Any?> = context.toMutableMap()
    override val isTerminated: Boolean = false
    override val trace: List<TraceEntry> = emptyList()
}
