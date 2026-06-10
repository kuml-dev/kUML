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
 */
@Serializable
public data class ActivityInstanceSnapshot(
    val modelId: String,
    val modelFingerprint: String,
    val instance: ActivityInstance,
    val schemaVersion: Int = 1,
)
