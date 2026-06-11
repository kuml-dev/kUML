package dev.kuml.runtime.sandbox

import dev.kuml.runtime.ActionPhase
import dev.kuml.runtime.EffectInvoker
import dev.kuml.runtime.Event
import dev.kuml.runtime.InvocationOutcome
import dev.kuml.runtime.StateMachineInstance

/**
 * Bridges [EffectInvoker] (core) to [EffectExecutor] (sandbox).
 *
 * Any [SandboxException] thrown by the executor is converted into
 * [InvocationOutcome.Error] so the runtime's rollback path is triggered.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public class SandboxEffectInvoker(
    private val executor: EffectExecutor,
) : EffectInvoker {
    override fun invoke(
        action: String,
        phase: ActionPhase,
        vertexId: String?,
        transitionId: String?,
        instance: StateMachineInstance,
        event: Event,
    ): InvocationOutcome =
        try {
            executor.execute(action, instance, event)
            InvocationOutcome.Success
        } catch (ex: SandboxException) {
            InvocationOutcome.Error(ex.message ?: "sandbox error", ex)
        }
}
