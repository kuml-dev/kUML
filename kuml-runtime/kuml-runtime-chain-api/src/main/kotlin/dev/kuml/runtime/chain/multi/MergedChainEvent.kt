package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.ChainEvent

/**
 * V3.0.21 — Ein [ChainEvent], angereichert um Multi-Chain-Merge-Metadaten.
 *
 * Kein `data class`: [ChainEvent] enthält ein `ByteArray` (payloadAbi) und nutzt
 * bereits content-basiertes equals/hashCode. Ein generiertes data-class-equals würde
 * referenz-basiert vergleichen → Test-Assertions brechen. Daher manuelles
 * equals/hashCode, das an [ChainEvent.equals] delegiert.
 *
 * @property chainId Logischer Chain-Bezeichner (z.B. "evm", "sui", "aptos").
 * @property globalSequence Eindeutige, monoton steigende Positions-Nummer, die jedem
 *   **upstream-Event** beim Eintreffen im Merge zugewiesen wird (≥ 0). Vergeben durch
 *   [MultiChainAdapter.subscribeAll]; nicht chain-übergreifend kausal, sondern reine
 *   Beobachtungs-Reihenfolge im Merge.
 *
 *   **Wichtig:** Die Monotonie-Garantie gilt *pro upstream-Event*, NICHT über den
 *   vollständigen Ausgabe-Stream. Bei einem erkannten Konflikt (bei dem das bereits
 *   emittierte rival-Event verliert) wird ein nachträgliches LOSER-Tombstone-Event
 *   emittiert, das dieselbe `globalSequence` wie das originale [ConflictRole.NONE]-Event
 *   trägt. Der Stream enthält dann `[seq=X/NONE, seq=X/LOSER, seq=Y/WINNER]` — zwei
 *   Events mit identischem `seq=X`. Consumer, die *alle* Events (inkl. LOSER-Tombstones)
 *   verarbeiten, dürfen daher keine strenge Monotonie über den Gesamtstrom annehmen.
 *   Für Consumer, die LOSER-Events herausfiltern (was der empfohlene Umgang ist),
 *   ist die Sequenz der verbleibenden Events aufsteigend eindeutig.
 * @property originalEvent Das ursprüngliche, unveränderte On-Chain-Event.
 * @property conflictRole Rolle dieses Events bei Konfliktauflösung:
 *   - [ConflictRole.NONE]: kein Konflikt erkannt, Event passiert normal.
 *   - [ConflictRole.WINNER]: Konflikt erkannt, dieses Event hat gewonnen.
 *   - [ConflictRole.LOSER]: Konflikt erkannt, dieses Event hat verloren.
 *   Im Konfliktfall werden BEIDE Events emittiert (LOSER zuerst, da er bereits im
 *   Fenster war; WINNER danach). Downstream-Consumer sollen LOSER-Events ignorieren.
 */
public class MergedChainEvent(
    public val chainId: String,
    public val globalSequence: Long,
    public val originalEvent: ChainEvent,
    public val conflictRole: ConflictRole = ConflictRole.NONE,
) {
    /** Convenience: Block des zugrundeliegenden Events. */
    public val blockNumber: Long get() = originalEvent.blockNumber

    /** Convenience: eventType des zugrundeliegenden Events. */
    public val eventType: String get() = originalEvent.eventType

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MergedChainEvent) return false
        return chainId == other.chainId &&
            globalSequence == other.globalSequence &&
            conflictRole == other.conflictRole &&
            originalEvent == other.originalEvent // delegiert an ChainEvent.equals (contentEquals)
    }

    override fun hashCode(): Int {
        var r = chainId.hashCode()
        r = 31 * r + globalSequence.hashCode()
        r = 31 * r + conflictRole.hashCode()
        r = 31 * r + originalEvent.hashCode()
        return r
    }

    override fun toString(): String =
        "MergedChainEvent(chainId='$chainId', globalSequence=$globalSequence, " +
            "conflictRole=$conflictRole, originalEvent=$originalEvent)"
}

/**
 * Rolle eines [MergedChainEvent] bei der Konfliktauflösung in [MultiChainAdapter.subscribeAll].
 *
 * Da bereits emittierte Events nicht rückwirkend zurückgezogen werden können (push-only Flow),
 * werden bei einem erkannten Konflikt BEIDE Events emittiert — der Verlierer mit [LOSER],
 * der Gewinner danach mit [WINNER]. Downstream-Consumer filtern LOSER-Events heraus.
 */
public enum class ConflictRole {
    /** Kein Konflikt — normaler Durchlauf. */
    NONE,

    /** Konflikt erkannt, dieses Event hat gewonnen — soll downstream verarbeitet werden. */
    WINNER,

    /**
     * Konflikt erkannt nachträglich, dieses Event hat verloren — soll downstream ignoriert
     * werden. Das Event wurde bereits emittiert (bevor das konfligierende Event ankam) und
     * kann nicht zurückgezogen werden; der Consumer ist verantwortlich, LOSER zu verwerfen.
     */
    LOSER,
}
