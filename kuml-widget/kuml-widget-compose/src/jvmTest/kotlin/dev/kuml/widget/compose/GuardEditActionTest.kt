package dev.kuml.widget.compose

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure logic tests for [resolveGuardEditAction] — the extracted, testable
 * Save-time decision [ControlPanel] uses so it never has to branch on
 * [GuardEditGate] (or [EditPolicy]) directly.
 *
 * [resolveGuardEditAction] is a thin 1:1 mapping over [EditPolicy.guardEditGate],
 * so these tests mirror [GuardEditGateTest] rather than re-deriving the gate
 * logic, and additionally lock the exact [GuardEditGate] -> [GuardEditAction]
 * mapping (including the defensive, UI-unreachable [GuardEditGate.Denied] ->
 * [GuardEditAction.Denied] case).
 */
class GuardEditActionTest :
    FunSpec(body = {

        val unprotected = UmlTransition(id = "t1", sourceId = "A", targetId = "B")
        val protected =
            UmlTransition(
                id = "t2",
                sourceId = "A",
                targetId = "B",
                metadata = mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true)),
            )

        test(name = "None + unprotected => Denied") {
            resolveGuardEditAction(policy = EditPolicy.None, transition = unprotected) shouldBe GuardEditAction.Denied
        }

        test(name = "None + protected => Denied (protected cannot grant access)") {
            resolveGuardEditAction(policy = EditPolicy.None, transition = protected) shouldBe GuardEditAction.Denied
        }

        test(name = "GuardsOnly + unprotected => Apply") {
            resolveGuardEditAction(policy = EditPolicy.GuardsOnly, transition = unprotected) shouldBe GuardEditAction.Apply
        }

        test(name = "GuardsOnly + protected => Confirm") {
            resolveGuardEditAction(policy = EditPolicy.GuardsOnly, transition = protected) shouldBe GuardEditAction.Confirm
        }

        test(name = "FullStructural + unprotected => Apply") {
            resolveGuardEditAction(policy = EditPolicy.FullStructural, transition = unprotected) shouldBe GuardEditAction.Apply
        }

        test(name = "FullStructural + protected => Confirm") {
            resolveGuardEditAction(policy = EditPolicy.FullStructural, transition = protected) shouldBe GuardEditAction.Confirm
        }

        test(name = "every GuardEditGate value maps onto exactly one GuardEditAction (no silent fallthrough)") {
            GuardEditGate.entries.forEach { gate ->
                val policy =
                    when (gate) {
                        GuardEditGate.Denied -> EditPolicy.None
                        GuardEditGate.Allowed -> EditPolicy.GuardsOnly
                        GuardEditGate.RequiresConfirmation -> EditPolicy.GuardsOnly
                    }
                val transition = if (gate == GuardEditGate.RequiresConfirmation) protected else unprotected
                val expected =
                    when (gate) {
                        GuardEditGate.Denied -> GuardEditAction.Denied
                        GuardEditGate.Allowed -> GuardEditAction.Apply
                        GuardEditGate.RequiresConfirmation -> GuardEditAction.Confirm
                    }

                resolveGuardEditAction(policy = policy, transition = transition) shouldBe expected
            }
        }
    })
