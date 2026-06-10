package dev.kuml.runtime.snapshot

import dev.kuml.runtime.Event
import dev.kuml.runtime.TraceEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Vollständiger, serialisierbarer Snapshot einer [StateMachineInstance].
 *
 * Enthält — anders als der einfache [dev.kuml.runtime.Snapshot] — auch
 * die interne Queue, den kompletten Trace und das [isTerminated]-Flag.
 * Damit ist eine verlustfreie Wiederherstellung möglich.
 *
 * @property modelId Identifier des Modells (UmlStateMachine.id).
 * @property modelFingerprint SHA-256-Fingerprint des Modells zum Zeitpunkt
 *   des Snapshots; wird bei [restoreFrom] zur Kompatibilitätsprüfung genutzt.
 * @property currentVertexIds IDs der aktuell aktiven Vertices.
 * @property variables Modell-Variablen als JSON-Element-Map.
 * @property internalQueue Interne Event-Queue (FIFO).
 * @property trace Vollständiger Trace (alle TraceEntry-Objekte).
 * @property seqCounter Aktueller Sequenz-Zähler.
 * @property isTerminated Ob die State Machine terminiert ist.
 * @property schemaVersion Versionsnummer für Forward-Kompatibilität.
 */
@Serializable
public data class StateMachineSnapshot(
    val modelId: String,
    val modelFingerprint: String,
    val currentVertexIds: List<String>,
    val variables: Map<String, JsonElement>,
    val internalQueue: List<Event>,
    val trace: List<TraceEntry>,
    val seqCounter: Long,
    val isTerminated: Boolean,
    val schemaVersion: Int = 1,
)
