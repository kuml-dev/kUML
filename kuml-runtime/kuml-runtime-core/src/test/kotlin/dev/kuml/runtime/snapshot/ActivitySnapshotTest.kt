package dev.kuml.runtime.snapshot

import dev.kuml.runtime.activity.ActivityEdgeSpec
import dev.kuml.runtime.activity.ActivityInstance
import dev.kuml.runtime.activity.ActivityNodeSpec
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.activity.ActivityRuntimeSpec
import dev.kuml.sysml2.ActivityNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

/**
 * Tests für Activity-Snapshots: snapshotFull, restoreFrom, Fingerprint-Policy.
 */
class ActivitySnapshotTest :
    FunSpec({
        fun node(
            id: String,
            kind: ActivityNodeKind,
        ) = ActivityNodeSpec(id = id, kind = kind)

        fun edge(
            id: String,
            from: String,
            to: String,
        ) = ActivityEdgeSpec(id = id, sourceNodeId = from, targetNodeId = to)

        val specV1 =
            ActivityRuntimeSpec(
                nodes =
                    mapOf(
                        "n_init" to node("n_init", ActivityNodeKind.Initial),
                        "n_action" to node("n_action", ActivityNodeKind.Action),
                        "n_final" to node("n_final", ActivityNodeKind.Final),
                    ),
                edges =
                    listOf(
                        edge("e1", "n_init", "n_action"),
                        edge("e2", "n_action", "n_final"),
                    ),
            )

        val specV2WithAddedNode =
            ActivityRuntimeSpec(
                nodes =
                    mapOf(
                        "n_init" to node("n_init", ActivityNodeKind.Initial),
                        "n_action" to node("n_action", ActivityNodeKind.Action),
                        "n_extra" to node("n_extra", ActivityNodeKind.Action), // added
                        "n_final" to node("n_final", ActivityNodeKind.Final),
                    ),
                edges =
                    listOf(
                        edge("e1", "n_init", "n_action"),
                        edge("e2", "n_action", "n_final"),
                        edge("e3", "n_action", "n_extra"),
                    ),
            )

        val runtimeV1 = ActivityRuntime(specV1)

        val modelId = "TestActivity"

        test("roundtrip ActivityInstance preserves tokenCounts") {
            val (instance, _) = runtimeV1.start()
            // After start, activity may be terminated (simple linear flow)
            // Use an intermediate instance with tokens
            val instanceWithToken = ActivityInstance(tokenCounts = mapOf("n_action" to 1), clock = 3L)

            val currentFingerprint = fingerprintActivity(specV1.nodes.keys, specV1.edges.map { it.id }.toSet())
            val snap = runtimeV1.snapshotFull(instanceWithToken, modelId, currentFingerprint)

            snap.modelId shouldBe modelId
            snap.schemaVersion shouldBe 1
            snap.instance.tokenCounts shouldContainKey "n_action"
            snap.instance.tokenCounts["n_action"] shouldBe 1
            snap.instance.clock shouldBe 3L
        }

        test("restoreFrom rejects modified model under Reject policy") {
            val instanceWithToken = ActivityInstance(tokenCounts = mapOf("n_action" to 1), clock = 2L)
            val fingerprintV1 = fingerprintActivity(specV1.nodes.keys, specV1.edges.map { it.id }.toSet())
            val snap = runtimeV1.snapshotFull(instanceWithToken, modelId, fingerprintV1)

            val runtimeV2 = ActivityRuntime(specV2WithAddedNode)
            shouldThrow<MigrationException> {
                runtimeV2.restoreFrom(snap, MigrationPolicy.Reject)
            }
        }

        test("restoreFrom accepts identical model") {
            val instanceWithToken = ActivityInstance(tokenCounts = mapOf("n_action" to 1), clock = 7L)
            val currentFingerprint = fingerprintActivity(specV1.nodes.keys, specV1.edges.map { it.id }.toSet())
            val snap = runtimeV1.snapshotFull(instanceWithToken, modelId, currentFingerprint)

            val restored = runtimeV1.restoreFrom(snap, MigrationPolicy.Reject)
            restored.tokenCounts shouldBe instanceWithToken.tokenCounts
            restored.clock shouldBe 7L
        }
    })
