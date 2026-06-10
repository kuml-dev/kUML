package dev.kuml.runtime.snapshot

import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.initial
import dev.kuml.runtime.smOf
import dev.kuml.runtime.state
import dev.kuml.runtime.trans
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests für alle [MigrationPolicy]-Implementierungen.
 */
class MigrationPolicyTest :
    FunSpec({
        val modelV1 =
            smOf(
                name = "MigSM",
                vertices =
                    listOf(
                        initial("init"),
                        state("A"),
                        state("B"),
                    ),
                transitions =
                    listOf(
                        trans("t0", "init", "A"),
                        trans("t1", "A", "B", trigger = "go"),
                    ),
            )

        val modelV2WithAddedVertex =
            smOf(
                name = "MigSM",
                vertices =
                    listOf(
                        initial("init"),
                        state("A"),
                        state("B"),
                        state("C"), // added
                    ),
                transitions =
                    listOf(
                        trans("t0", "init", "A"),
                        trans("t1", "A", "B", trigger = "go"),
                        trans("t2", "B", "C", trigger = "next"),
                    ),
            )

        val modelV2WithRemovedVertex =
            smOf(
                name = "MigSM",
                vertices =
                    listOf(
                        initial("init"),
                        state("A"),
                        // B removed
                    ),
                transitions =
                    listOf(
                        trans("t0", "init", "A"),
                    ),
            )

        val runtime = StateMachineRuntime()

        test("Reject throws MigrationException on fingerprint mismatch") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            shouldThrow<MigrationException> {
                runtime.restoreFrom(modelV2WithAddedVertex, snap, MigrationPolicy.Reject)
            }
        }

        test("AcceptIfFingerprintMatches accepts identical model") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            // Should not throw — same model, same fingerprint
            val restored = runtime.restoreFrom(modelV1, snap, MigrationPolicy.AcceptIfFingerprintMatches)
            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
        }

        test("AcceptIfFingerprintMatches throws on mismatch") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            shouldThrow<MigrationException> {
                runtime.restoreFrom(modelV2WithAddedVertex, snap, MigrationPolicy.AcceptIfFingerprintMatches)
            }
        }

        test("AcceptIfVerticesPresent accepts model with added vertex") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            // The snapshot's active vertex (A) is still present in V2 — should succeed
            val restored = runtime.restoreFrom(modelV2WithAddedVertex, snap, MigrationPolicy.AcceptIfVerticesPresent())
            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
        }

        test("AcceptIfVerticesPresent rejects removed vertex") {
            // Start in modelV2 where B exists, then try to restore on model without B
            val instance = runtime.start(modelV2WithAddedVertex)
            // Fire go to move to B
            runtime.step(instance, Event.of("go"))
            val snap = runtime.snapshotFull(instance)
            // Instance is at B, but modelV2WithRemovedVertex doesn't have B
            shouldThrow<MigrationException> {
                runtime.restoreFrom(modelV2WithRemovedVertex, snap, MigrationPolicy.AcceptIfVerticesPresent())
            }
        }

        test("Custom policy delegates to predicate") {
            var called = false
            val customPolicy =
                MigrationPolicy.Custom { _, _, _, _ ->
                    called = true
                    // does not throw — accepts everything
                }
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            runtime.restoreFrom(modelV2WithAddedVertex, snap, customPolicy)
            called shouldBe true
        }
    })
