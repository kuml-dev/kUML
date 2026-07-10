package dev.kuml.widget.compose

import dev.kuml.runtime.ModelPatch
import dev.kuml.runtime.PatchResult
import dev.kuml.runtime.applyPatch
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.uml.UmlTransition

/**
 * Widget-level outcome of a guard edit — decoupled from the runtime's
 * [PatchResult] subtypes so [ControlPanel] never needs to branch on
 * runtime-internal rejection reasons.
 */
public sealed interface PatchOutcome {
    /** The guard edit was applied; [BehaviourWidgetState.model] now reflects it. */
    public data object Applied : PatchOutcome

    /**
     * The target transition is protected and [confirmed][changeGuard] was not
     * (yet) `true`. The UI should show a confirmation dialog and retry with
     * `confirmed = true`.
     */
    public data object NeedsConfirmation : PatchOutcome

    /** The edit was rejected; [message] is a human-readable reason. */
    public data class Rejected(public val message: String) : PatchOutcome
}

/** A guard edit awaiting user confirmation because its target transition is protected. */
public data class PendingGuardEdit(
    public val transitionId: String,
    public val newOcl: String,
)

/**
 * Applies a guard change to [transitionId] via the runtime's typed
 * [ModelPatch.ChangeGuard] path, swapping [BehaviourWidgetState.model] (and the
 * underlying live instance) in on success — which in turn triggers Compose
 * recomposition of any layout/SVG derived from the model.
 *
 * Blocked while [BehaviourWidgetState.isScrubbing]: guard edits mutate the live
 * instance, which is invisible during history review, so editing is rejected
 * until the caller returns to live (e.g. `scrubTo(trace.size)`).
 *
 * Protected transitions require [confirmed] `== true`; without it the outcome
 * is [PatchOutcome.NeedsConfirmation] and nothing changes. This mirrors (and is
 * enforced again by) the runtime's own protected gate in
 * [dev.kuml.runtime.applyPatch] — defense in depth.
 *
 * @param transitionId id of the transition whose guard is being edited.
 * @param newOcl the new guard OCL expression (blank clears the guard).
 * @param confirmed must be `true` to edit a guard on a protected transition.
 */
public fun BehaviourWidgetState.changeGuard(
    transitionId: String,
    newOcl: String,
    confirmed: Boolean = false,
): PatchOutcome {
    if (!editPolicy.allowsGuardEdit) {
        return PatchOutcome.Rejected("guard editing not permitted by policy")
    }
    if (isScrubbing) {
        return PatchOutcome.Rejected("cannot edit a guard while scrubbing; return to live first")
    }

    val result = runtime.applyPatch(
        instance = _instance,
        patch = ModelPatch.ChangeGuard(transitionId, newOcl),
        policy = MigrationPolicy.Reject,
        confirmed = confirmed,
    )
    return when (result) {
        is PatchResult.Applied -> {
            _instance = result.instance
            model = result.model
            syncTrace()
            PatchOutcome.Applied
        }
        is PatchResult.Rejected.ProtectedNotConfirmed -> PatchOutcome.NeedsConfirmation
        is PatchResult.Rejected -> PatchOutcome.Rejected(result.message)
    }
}

/**
 * The action [ControlPanel] should take when the user hits "Save" on a guard
 * edit for [transition], derived purely from [EditPolicy.guardEditGate].
 *
 * Thin, testable wrapper: [GuardEditGate.Denied] should be unreachable from the
 * UI (the edit entry point is itself gated), but is mapped defensively anyway.
 */
public enum class GuardEditAction {
    /** Apply the edit immediately (`confirmed = false`). */
    Apply,

    /** Show a confirmation dialog before applying (`confirmed = true` on confirm). */
    Confirm,

    /** The policy does not permit this edit at all. */
    Denied,
}

/** Maps [EditPolicy.guardEditGate] onto the UI-facing [GuardEditAction]. */
public fun resolveGuardEditAction(
    policy: EditPolicy,
    transition: UmlTransition,
): GuardEditAction =
    when (policy.guardEditGate(transition)) {
        GuardEditGate.Denied -> GuardEditAction.Denied
        GuardEditGate.Allowed -> GuardEditAction.Apply
        GuardEditGate.RequiresConfirmation -> GuardEditAction.Confirm
    }
