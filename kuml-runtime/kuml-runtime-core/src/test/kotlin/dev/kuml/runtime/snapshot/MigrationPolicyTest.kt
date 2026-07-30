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
                        state(id = "A"),
                        state(id = "B"),
                    ),
                transitions =
                    listOf(
                        trans(id = "t0", from = "init", to = "A"),
                        trans(id = "t1", from = "A", to = "B", trigger = "go"),
                    ),
            )

        val modelV2WithAddedVertex =
            smOf(
                name = "MigSM",
                vertices =
                    listOf(
                        initial("init"),
                        state(id = "A"),
                        state(id = "B"),
                        state(id = "C"), // added
                    ),
                transitions =
                    listOf(
                        trans(id = "t0", from = "init", to = "A"),
                        trans(id = "t1", from = "A", to = "B", trigger = "go"),
                        trans(id = "t2", from = "B", to = "C", trigger = "next"),
                    ),
            )

        val modelV2WithRemovedVertex =
            smOf(
                name = "MigSM",
                vertices =
                    listOf(
                        initial("init"),
                        state(id = "A"),
                        // B removed
                    ),
                transitions =
                    listOf(
                        trans(id = "t0", from = "init", to = "A"),
                    ),
            )

        val runtime = StateMachineRuntime()

        test("Reject throws MigrationException on fingerprint mismatch") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            shouldThrow<MigrationException> {
                runtime.restoreFrom(model = modelV2WithAddedVertex, snapshot = snap, policy = MigrationPolicy.Reject)
            }
        }

        test("AcceptIfFingerprintMatches accepts identical model") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            // Should not throw — same model, same fingerprint
            val restored = runtime.restoreFrom(model = modelV1, snapshot = snap, policy = MigrationPolicy.AcceptIfFingerprintMatches)
            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
        }

        test("AcceptIfFingerprintMatches throws on mismatch") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            shouldThrow<MigrationException> {
                runtime.restoreFrom(model = modelV2WithAddedVertex, snapshot = snap, policy = MigrationPolicy.AcceptIfFingerprintMatches)
            }
        }

        test("AcceptIfVerticesPresent accepts model with added vertex") {
            val instance = runtime.start(modelV1)
            val snap = runtime.snapshotFull(instance)
            // The snapshot's active vertex (A) is still present in V2 — should succeed
            val restored =
                runtime.restoreFrom(
                    model = modelV2WithAddedVertex,
                    snapshot = snap,
                    policy = MigrationPolicy.AcceptIfVerticesPresent(),
                )
            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
        }

        test("AcceptIfVerticesPresent rejects removed vertex") {
            // Start in modelV2 where B exists, then try to restore on model without B
            val instance = runtime.start(modelV2WithAddedVertex)
            // Fire go to move to B
            runtime.step(instance = instance, event = Event.of("go"))
            val snap = runtime.snapshotFull(instance)
            // Instance is at B, but modelV2WithRemovedVertex doesn't have B
            shouldThrow<MigrationException> {
                runtime.restoreFrom(model = modelV2WithRemovedVertex, snapshot = snap, policy = MigrationPolicy.AcceptIfVerticesPresent())
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
            runtime.restoreFrom(model = modelV2WithAddedVertex, snapshot = snap, policy = customPolicy)
            called shouldBe true
        }
    })
