package dev.kuml.runtime.snapshot

import dev.kuml.runtime.ModelPatch
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [MigrationPolicy.onPatch] — the structural (vertex-preservation)
 * gate invoked by `applyPatch` (Wave 3), distinct from [MigrationPolicy.check]
 * (snapshot-restore/fingerprint oriented).
 */
class MigrationPolicyOnPatchTest :
    FunSpec({
        val patch = ModelPatch.ChangeGuard(transitionId = "t1", newOcl = "true")

        test("Reject.onPatch does not throw when the patch preserves all active vertices") {
            shouldNotThrowAny {
                MigrationPolicy.Reject.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("A", "B"))
            }
        }

        test("Reject.onPatch throws when the patch would remove an active vertex") {
            val ex =
                shouldThrow<MigrationException> {
                    MigrationPolicy.Reject.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("B"))
                }
            ex.reason shouldBe "patch would remove active vertices"
        }

        test("AcceptIfVerticesPresent.onPatch inherits the default structural check") {
            val policy = MigrationPolicy.AcceptIfVerticesPresent()
            shouldNotThrowAny {
                policy.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("A"))
            }
            shouldThrow<MigrationException> {
                policy.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("B"))
            }
        }

        test("Custom.onPatch inherits the default structural check (predicate only overrides check)") {
            // The predicate would accept everything via check() — onPatch is unaffected.
            val policy = MigrationPolicy.Custom { _, _, _, _ -> }
            shouldNotThrowAny {
                policy.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("A"))
            }
            shouldThrow<MigrationException> {
                policy.onPatch(patch, activeVertexIds = listOf("A"), patchedVertexIds = setOf("B"))
            }
        }
    })
