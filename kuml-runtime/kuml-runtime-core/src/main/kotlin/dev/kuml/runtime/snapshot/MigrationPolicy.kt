package dev.kuml.runtime.snapshot

/**
 * Strategie, die entscheidet ob ein [StateMachineSnapshot] oder
 * [ActivityInstanceSnapshot] auf ein (potenziell verändertes) Modell
 * angewendet werden darf.
 *
 * Implementierungen werfen [MigrationException] bei Ablehnung oder kehren
 * still zurück bei Akzeptanz.
 */
public sealed interface MigrationPolicy {
    /**
     * Prüft Kompatibilität zwischen Snapshot und aktuellem Modell.
     *
     * @param snapshotFingerprint Fingerprint des Modells zum Snapshot-Zeitpunkt.
     * @param currentFingerprint Fingerprint des aktuell verwendeten Modells.
     * @param snapshotVertexIds Vertex-IDs, die im Snapshot als aktiv gespeichert sind.
     * @param currentVertexIds Alle Vertex-IDs im aktuellen Modell.
     * @throws MigrationException wenn der Snapshot abgelehnt wird.
     */
    public fun check(
        snapshotFingerprint: String,
        currentFingerprint: String,
        snapshotVertexIds: List<String>,
        currentVertexIds: Set<String>,
    )

    /**
     * Wirft bei ANY Mismatch sofort [MigrationException] — Default-Policy.
     * Garantiert, dass Snapshot und Modell exakt übereinstimmen.
     */
    public data object Reject : MigrationPolicy {
        override fun check(
            snapshotFingerprint: String,
            currentFingerprint: String,
            snapshotVertexIds: List<String>,
            currentVertexIds: Set<String>,
        ) {
            if (snapshotFingerprint != currentFingerprint) {
                throw MigrationException(
                    reason = "fingerprint mismatch",
                    expected = snapshotFingerprint,
                    actual = currentFingerprint,
                )
            }
        }
    }

    /**
     * Identisch mit [Reject] — expliziterer Name für lesbareren Code.
     * Akzeptiert nur wenn Fingerprints übereinstimmen.
     */
    public data object AcceptIfFingerprintMatches : MigrationPolicy {
        override fun check(
            snapshotFingerprint: String,
            currentFingerprint: String,
            snapshotVertexIds: List<String>,
            currentVertexIds: Set<String>,
        ) {
            if (snapshotFingerprint != currentFingerprint) {
                throw MigrationException(
                    reason = "fingerprint mismatch",
                    expected = snapshotFingerprint,
                    actual = currentFingerprint,
                )
            }
        }
    }

    /**
     * Erlaubt zusätzliche Vertices im neuen Modell (additive Modelländerung),
     * solange alle Snapshot-Vertices noch im aktuellen Modell vorhanden sind.
     * Wirft [MigrationException] wenn ein im Snapshot aktiver Vertex im
     * aktuellen Modell fehlt (destructive change).
     */
    public class AcceptIfVerticesPresent : MigrationPolicy {
        override fun check(
            snapshotFingerprint: String,
            currentFingerprint: String,
            snapshotVertexIds: List<String>,
            currentVertexIds: Set<String>,
        ) {
            val missing = snapshotVertexIds.filter { it !in currentVertexIds }
            if (missing.isNotEmpty()) {
                throw MigrationException(
                    reason = "snapshot vertex ids missing from current model",
                    expected = snapshotVertexIds.joinToString(","),
                    actual = "missing: ${missing.joinToString(",")}",
                )
            }
        }
    }

    /**
     * Benutzerdefinierte Policy — delegiert an ein Prädikat.
     * Das Prädikat soll [MigrationException] werfen bei Ablehnung.
     */
    public class Custom(
        private val predicate: (
            snapshotFingerprint: String,
            currentFingerprint: String,
            snapshotVertexIds: List<String>,
            currentVertexIds: Set<String>,
        ) -> Unit,
    ) : MigrationPolicy {
        override fun check(
            snapshotFingerprint: String,
            currentFingerprint: String,
            snapshotVertexIds: List<String>,
            currentVertexIds: Set<String>,
        ): Unit = predicate(snapshotFingerprint, currentFingerprint, snapshotVertexIds, currentVertexIds)
    }
}
