package dev.kuml.widget.compose

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure logic tests for [EditPolicy.guardEditGate] — no Compose UI involved.
 *
 * Locks the orthogonality invariant: "protected" only ever tightens the gate
 * (adds a confirmation step), never grants access a policy level denies.
 */
class GuardEditGateTest :
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
            EditPolicy.None.guardEditGate(unprotected) shouldBe GuardEditGate.Denied
        }

        test(name = "None + protected => Denied (protected cannot grant access)") {
            EditPolicy.None.guardEditGate(protected) shouldBe GuardEditGate.Denied
        }

        test(name = "GuardsOnly + unprotected => Allowed") {
            EditPolicy.GuardsOnly.guardEditGate(unprotected) shouldBe GuardEditGate.Allowed
        }

        test(name = "GuardsOnly + protected => RequiresConfirmation") {
            EditPolicy.GuardsOnly.guardEditGate(protected) shouldBe GuardEditGate.RequiresConfirmation
        }

        test(name = "FullStructural + unprotected => Allowed") {
            EditPolicy.FullStructural.guardEditGate(unprotected) shouldBe GuardEditGate.Allowed
        }

        test(name = "FullStructural + protected => RequiresConfirmation") {
            EditPolicy.FullStructural.guardEditGate(protected) shouldBe GuardEditGate.RequiresConfirmation
        }
    })
