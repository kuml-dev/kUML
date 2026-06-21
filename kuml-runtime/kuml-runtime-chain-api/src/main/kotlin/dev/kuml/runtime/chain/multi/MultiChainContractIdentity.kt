package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.ContractIdentity

/**
 * V3.0.21 — Aggregierte Identität desselben kUML-Contracts über mehrere Chains.
 *
 * @property identities chainId → [ContractIdentity] der jeweiligen Chain.
 * @property primaryModelHash modelHash der Primary-Chain (erste Map-Iterationsreihenfolge).
 *   Defensiv kopiert (ByteArray ist mutable).
 * @property consistent true, wenn ALLE Chains denselben modelHash melden — d.h. überall
 *   ist dasselbe kUML-Modell deployed. false signalisiert Modell-Drift zwischen Chains.
 *
 * **Wichtig bei consistent = false (Modell-Drift):** [MultiChainAdapter.subscribeAll] und
 * [MultiChainAdapter.replayAll] laufen auch bei Drift **ohne Fehler weiter** — der Adapter
 * bricht nicht automatisch ab. Der Aufrufer ist verantwortlich, [consistent] nach
 * [MultiChainAdapter.connectAll] zu prüfen und bei `false` eine geeignete Fehlerbehandlung
 * durchzuführen, z.B. Abbruch der Verarbeitung, Alarm an das Operations-Team oder manuelle
 * Reconciliation der divergenten Chains. Wer [consistent] ignoriert, riskiert, Events aus
 * Chains mit einem veralteten oder kompromittierten Modell-Deployment zu verarbeiten.
 */
public class MultiChainContractIdentity private constructor(
    public val identities: Map<String, ContractIdentity>,
    primaryModelHash: ByteArray,
    public val consistent: Boolean,
) {
    private val _primaryModelHash: ByteArray = primaryModelHash.copyOf()

    /** Defensive Kopie bei jedem Zugriff (ByteArray ist mutable). */
    public val primaryModelHash: ByteArray get() = _primaryModelHash.copyOf()

    public companion object {
        /**
         * Baut die aggregierte Identität.
         *
         * Primary-Chain = erster Eintrag der Map-Iterationsreihenfolge. Aufrufer, die
         * eine bestimmte Primary erzwingen wollen, übergeben eine [LinkedHashMap] mit
         * gewünschter Reihenfolge (siehe [MultiChainAdapter.connectAll], das eine
         * LinkedHashMap in Adapter-Reihenfolge liefert).
         *
         * @throws IllegalArgumentException wenn [map] leer ist.
         */
        public fun from(map: Map<String, ContractIdentity>): MultiChainContractIdentity {
            require(map.isNotEmpty()) { "MultiChainContractIdentity erfordert mindestens eine Chain" }
            val primary = map.values.first()
            val allConsistent =
                map.values.all { it.modelHash.contentEquals(primary.modelHash) }
            return MultiChainContractIdentity(
                identities = map.toMap(), // defensive Kopie, Reihenfolge erhalten
                primaryModelHash = primary.modelHash,
                consistent = allConsistent,
            )
        }
    }

    override fun toString(): String = "MultiChainContractIdentity(chains=${identities.keys}, consistent=$consistent)"
}
