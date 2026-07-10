package dev.kuml.runtime

import dev.kuml.core.ocl.OclCheckResult
import dev.kuml.core.ocl.OclScope
import dev.kuml.core.ocl.OclSyntax
import dev.kuml.core.ocl.OclType
import dev.kuml.runtime.internal.allVertices
import dev.kuml.runtime.internal.buildParentOf
import dev.kuml.runtime.snapshot.MigrationException
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.isProtected

/**
 * Interactive-widget/runtime model-edit patch. Deliberately SEPARATE from the
 * kuml-ai-tools ModelPatch (AI-editing audit log) — different concern, different
 * shape, different module. MVP carries only [ChangeGuard].
 */
public sealed interface ModelPatch {
    public data class ChangeGuard(
        public val transitionId: String,
        public val newOcl: String,
    ) : ModelPatch
}

/** Outcome of [applyPatch]. On [Applied] the returned instance/model replace the caller's. */
public sealed interface PatchResult {
    public data class Applied(
        public val instance: StateMachineInstance,
        public val model: UmlStateMachine,
        public val patch: ModelPatch,
    ) : PatchResult

    public sealed interface Rejected : PatchResult {
        public val message: String

        public data class TransitionNotFound(
            public val transitionId: String,
        ) : Rejected {
            override val message: String get() = "no transition with id '$transitionId'"
        }

        public data class InvalidOcl(
            public val transitionId: String,
            public val error: OclCheckResult.Error,
        ) : Rejected {
            override val message: String get() = "invalid OCL for '$transitionId': ${error.message}"
        }

        public data class ProtectedNotConfirmed(
            public val transitionId: String,
        ) : Rejected {
            override val message: String get() =
                "transition '$transitionId' is protected; pass confirmed=true to edit its guard"
        }

        public data class MigrationRejected(
            public val cause: MigrationException,
        ) : Rejected {
            override val message: String get() = cause.message ?: "migration policy rejected the patch"
        }
    }
}

/**
 * Validates and applies [patch] against [instance], returning a NEW instance on
 * success without ever partially mutating [instance] (atomic).
 *
 * Order: locate transition → static OCL type/size check → protected+confirm gate
 * → [MigrationPolicy.onPatch] → immutable model copy → rebuilt instance.
 *
 * Guard edits are fingerprint-transparent: [dev.kuml.runtime.snapshot.fingerprint]
 * hashes only vertex and transition ids, not guard strings, so a snapshot taken
 * before this patch will still [StateMachineRuntime.restoreFrom] cleanly onto the
 * post-patch model under [MigrationPolicy.Reject].
 *
 * @param confirmed MUST be true to edit a guard on a protected transition.
 */
public fun StateMachineRuntime.applyPatch(
    instance: StateMachineInstance,
    patch: ModelPatch,
    policy: MigrationPolicy = MigrationPolicy.Reject,
    confirmed: Boolean = false,
): PatchResult =
    when (patch) {
        is ModelPatch.ChangeGuard -> applyChangeGuard(instance, patch, policy, confirmed)
    }

private fun applyChangeGuard(
    instance: StateMachineInstance,
    patch: ModelPatch.ChangeGuard,
    policy: MigrationPolicy,
    confirmed: Boolean,
): PatchResult {
    val model = instance.model
    val idx = model.transitions.indexOfFirst { it.id == patch.transitionId }
    if (idx < 0) return PatchResult.Rejected.TransitionNotFound(patch.transitionId)
    val old = model.transitions[idx]

    // ── static OCL guard: size/complexity cap + scope/type check (blank ⇒ no guard)
    val normalized: String? = patch.newOcl.ifBlank { null }
    if (normalized != null) {
        when (val r = OclSyntax.typeCheck(normalized, defaultGuardScope())) {
            is OclCheckResult.Error -> return PatchResult.Rejected.InvalidOcl(patch.transitionId, r)
            OclCheckResult.Ok -> Unit
        }
    }

    // ── protected axis (orthogonal to EditPolicy level; see Wave 2 GuardEditGate)
    if (old.isProtected && !confirmed) {
        return PatchResult.Rejected.ProtectedNotConfirmed(patch.transitionId)
    }

    // ── immutable model copy
    val newTransition = old.copy(guard = normalized)
    val newModel =
        model.copy(
            transitions = model.transitions.toMutableList().apply { this[idx] = newTransition },
        )

    // ── migration gate (structural preservation of active vertices)
    val patchedVertexIds = allVertices(newModel).map { it.id }.toSet()
    try {
        policy.onPatch(patch, instance.currentVertexIds, patchedVertexIds)
    } catch (e: MigrationException) {
        return PatchResult.Rejected.MigrationRejected(e)
    }

    // ── atomically rebuild a fresh instance carrying live state over the new model
    val newInstance = instance.rebuildOnto(newModel)
    return PatchResult.Applied(newInstance, newModel, patch)
}

/**
 * Baseline OCL scope for interactive guard edits — mirrors [OclGuardEvaluator]'s
 * runtime env (`self` implicit; `event` + `vars` navigable OBJECTs). Single
 * source of truth shared by [applyPatch]'s static check and the widget editor,
 * so the editor's live type-check and applyPatch's static check can never
 * disagree.
 */
public fun defaultGuardScope(): OclScope = OclScope(mapOf("event" to OclType.OBJECT, "vars" to OclType.OBJECT))

/** Build a new instance over [newModel], copying this instance's live runtime state. */
internal fun StateMachineInstance.rebuildOnto(newModel: UmlStateMachine): StateMachineInstance {
    val parentOf = buildParentOf(newModel)
    val vertexById = allVertices(newModel).associateBy { it.id }
    val next = StateMachineInstance(model = newModel, parentOf = parentOf, vertexById = vertexById)
    for (id in this.currentVertexIds) {
        next.mutCurrentVertices +=
            vertexById[id]
                ?: error("active vertex '$id' vanished from patched model (should be caught by policy)")
    }
    next.variables.putAll(this.variables)
    next.mutInternalQueue.addAll(this.mutInternalQueue)
    next.mutTrace.addAll(this.mutTrace)
    next.seqCounter = this.seqCounter
    next.isTerminated = this.isTerminated
    return next
}
