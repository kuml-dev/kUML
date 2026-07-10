package dev.kuml.widget.compose

import dev.kuml.uml.UmlTransition
import dev.kuml.uml.isProtected

/**
 * Outcome of asking, under a given [EditPolicy], whether a transition guard may
 * be edited — and whether that edit must be confirmed first.
 *
 * "Protected" is an axis orthogonal to the [EditPolicy] level: a protected
 * transition always yields [RequiresConfirmation] as long as the policy permits
 * guard editing at all; if the policy forbids guard edits, the result is
 * [Denied] regardless of the protected flag (protected cannot grant access).
 */
public enum class GuardEditGate {
    /** The active [EditPolicy] does not permit guard edits at all. */
    Denied,

    /** Guard edit is permitted and may be applied directly. */
    Allowed,

    /** Guard edit is permitted but the transition is protected — confirm before applying. */
    RequiresConfirmation,
}

/**
 * Decides the [GuardEditGate] for editing [transition]'s guard under this policy.
 *
 * - `!allowsGuardEdit`            → [GuardEditGate.Denied]
 * - protected + guard edit ok     → [GuardEditGate.RequiresConfirmation]
 * - otherwise                     → [GuardEditGate.Allowed]
 */
public fun EditPolicy.guardEditGate(transition: UmlTransition): GuardEditGate =
    when {
        !allowsGuardEdit -> GuardEditGate.Denied
        transition.isProtected -> GuardEditGate.RequiresConfirmation
        else -> GuardEditGate.Allowed
    }
