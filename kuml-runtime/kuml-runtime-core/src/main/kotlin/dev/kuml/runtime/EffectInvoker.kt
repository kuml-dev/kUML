package dev.kuml.runtime

/**
 * Strategy for executing action bodies (entry, exit, do-activity, effect).
 *
 * The default [NoOp] implementation is a no-op — existing behaviour is
 * fully preserved when no invoker is provided.  The sandbox module provides
 * [dev.kuml.runtime.sandbox.SandboxEffectInvoker] as a safe runtime.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public fun interface EffectInvoker {
    /**
     * Invokes [action] in the given [phase] context.
     *
     * @param action   Raw action body string (entry/exit/doActivity/effect text).
     * @param phase    Which lifecycle phase this action belongs to.
     * @param vertexId ID of the vertex owning this action, or `null` for transitions.
     * @param transitionId ID of the transition owning this action, or `null` for states.
     * @param instance The live state-machine instance (variables may be mutated).
     * @param event    The event that triggered the current step.
     * @return [InvocationOutcome.Success] if the action completed normally,
     *   [InvocationOutcome.Error] if the action failed and the step should be rolled back.
     */
    public fun invoke(
        action: String,
        phase: ActionPhase,
        vertexId: String?,
        transitionId: String?,
        instance: StateMachineInstance,
        event: Event,
    ): InvocationOutcome

    public companion object {
        /** No-op invoker — actions are logged but not executed. Default behaviour. */
        public val NoOp: EffectInvoker =
            EffectInvoker { _, _, _, _, _, _ -> InvocationOutcome.Success }
    }
}

/** Result of an [EffectInvoker.invoke] call. */
public sealed interface InvocationOutcome {
    /** Action completed successfully. */
    public data object Success : InvocationOutcome

    /** Action failed; [message] describes the failure; the step should be rolled back. */
    public data class Error(
        public val message: String,
        public val cause: Throwable? = null,
    ) : InvocationOutcome
}

/** Thrown by [StateMachineRuntime] when an [EffectInvoker] returns [InvocationOutcome.Error]. */
internal class EffectInvocationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
