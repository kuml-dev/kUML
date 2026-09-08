package dev.kuml.runtime.snapshot

import dev.kuml.runtime.activity.ActivityInstance
import kotlinx.serialization.Serializable

/**
 * Vollständiger, serialisierbarer Snapshot einer [ActivityInstance].
 *
 * @property modelId Identifier des Activity-Modells.
 * @property modelFingerprint SHA-256-Fingerprint des Modells zum Zeitpunkt
 *   des Snapshots; wird bei [restoreFrom] zur Kompatibilitätsprüfung genutzt.
 * @property instance Die vollständige, bereits @Serializable ActivityInstance.
 * @property schemaVersion Versionsnummer für Forward-Kompatibilität.
 *
 * ## Restriction: not interchangeable between the two engines
 *
 * [ActivityInstance] carries two, engine-specific join-tracking fields:
 * [ActivityInstance.joinTokensReceived] (source-node based, written exclusively by
 * `dev.kuml.runtime.activity.ActivityRuntime`) and [ActivityInstance.joinEdgeTokens]
 * (edge based, written exclusively by `dev.kuml.runtime.tokenflow.TokenFlowEngine`).
 * A snapshot whose [instance] has [ActivityInstance.joinEdgeTokens] populated must
 * never be restored via `ActivityRuntime.restoreFrom` — that method's join-readiness
 * check reads only [ActivityInstance.joinTokensReceived], so the edge-level arrivals
 * would be silently ignored and the affected AND-join's tokens would never become
 * ready again. `ActivityRuntime.restoreFrom` enforces this unconditionally (a hard
 * [MigrationException], independent of the [dev.kuml.runtime.snapshot.MigrationPolicy]
 * passed to it) rather than merely documenting it. This is currently unreachable in
 * practice — `TokenFlowEngine` has no snapshot-writing path of its own yet — but the
 * guard exists so that gap does not become a live bug the moment one is added.
 */
@Serializable
public data class ActivityInstanceSnapshot(
    val modelId: String,
    val modelFingerprint: String,
    val instance: ActivityInstance,
    val schemaVersion: Int = 1,
)
