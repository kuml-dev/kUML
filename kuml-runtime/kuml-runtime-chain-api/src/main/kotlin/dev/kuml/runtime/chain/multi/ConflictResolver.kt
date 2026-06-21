package dev.kuml.runtime.chain.multi

/**
 * V3.0.21 — Deterministische Konfliktauflösung zwischen zwei konkurrierenden
 * [MergedChainEvent]s aus verschiedenen Chains.
 *
 * Vertrag (für ALLE Strategien zwingend):
 * - **Deterministisch**: resolve(a, b) hängt nur von a und b ab, nie von Wall-Clock,
 *   Hash-Iterationsreihenfolge oder Netzlatenz.
 * - **Total**: muss für JEDES Paar genau einen Gewinner liefern, niemals werfen.
 * - **Symmetrie-konsistent (Tie-Break)**: resolve(a, b) und resolve(b, a) müssen
 *   denselben Gewinner ergeben — sonst hängt das Ergebnis von der Argument-Reihenfolge
 *   ab und der Merge wird nicht reproduzierbar. Alle eingebauten Strategien erfüllen
 *   das durch eine vollständige Tie-Break-Kette bis hinunter zu txHash.
 */
public fun interface ConflictResolver {
    /** @return den Gewinner-Event (entweder [a] oder [b], nie ein neues Objekt). */
    public fun resolve(
        a: MergedChainEvent,
        b: MergedChainEvent,
    ): MergedChainEvent

    public companion object {
        /**
         * Letzte Tie-Break-Stufe, gemeinsam genutzt von allen Strategien.
         * Reihenfolge: blockNumber ↑ → chainId lexikografisch ↑ → txHash lexikografisch ↑.
         * txHash als letzter Anker garantiert Totalität auch bei identischer Chain+Block
         * (theoretisch; praktisch andere Chains → andere chainId schon vorher).
         */
        internal fun earliestBlockTieBreak(
            a: MergedChainEvent,
            b: MergedChainEvent,
        ): MergedChainEvent {
            val byBlock = a.blockNumber.compareTo(b.blockNumber)
            if (byBlock != 0) return if (byBlock < 0) a else b
            val byChain = a.chainId.compareTo(b.chainId)
            if (byChain != 0) return if (byChain < 0) a else b
            val byTx = a.originalEvent.txHash.compareTo(b.originalEvent.txHash)
            return if (byTx <= 0) a else b
        }

        /**
         * Kleinerer blockNumber gewinnt. Bei Gleichstand: lexikografisch kleinere chainId
         * (dann txHash). Vollständig deterministisch und symmetrie-konsistent.
         */
        public val EarliestBlock: ConflictResolver =
            ConflictResolver { a, b -> earliestBlockTieBreak(a, b) }

        /**
         * Das zuerst beobachtete Event (kleinerer globalSequence) gewinnt.
         * Bei Gleichstand (gleicher globalSequence — im Merge praktisch unmöglich,
         * aber Vertrag verlangt Totalität): Fallback auf [EarliestBlock].
         */
        public val FirstObserved: ConflictResolver =
            ConflictResolver { a, b ->
                val bySeq = a.globalSequence.compareTo(b.globalSequence)
                when {
                    bySeq < 0 -> a
                    bySeq > 0 -> b
                    else -> earliestBlockTieBreak(a, b)
                }
            }
    }

    /**
     * Priorität nach Chain-Reihenfolge: die Chain mit dem NIEDRIGSTEN Index in [priority]
     * gewinnt. Chains, die nicht in [priority] stehen, gelten als niedrigste Priorität
     * (Index = Int.MAX_VALUE). Bei gleichem Index (beide unbekannt oder — theoretisch —
     * gleicher Eintrag): Fallback auf [EarliestBlock].
     *
     * Eigene Klasse statt Companion-Val, weil sie Konfiguration ([priority]) trägt.
     */
    public class PriorityChain(
        priority: List<String>,
    ) : ConflictResolver {
        // defensive Kopie + Lookup-Map: chainId → Index, O(1) statt indexOf
        private val rank: Map<String, Int> =
            priority.withIndex().associate { (i, id) -> id to i }

        private fun rankOf(chainId: String): Int = rank[chainId] ?: Int.MAX_VALUE

        override fun resolve(
            a: MergedChainEvent,
            b: MergedChainEvent,
        ): MergedChainEvent {
            val ra = rankOf(a.chainId)
            val rb = rankOf(b.chainId)
            return when {
                ra < rb -> a
                rb < ra -> b
                else -> earliestBlockTieBreak(a, b) // gleicher Rang → Fallback
            }
        }

        /** Chain mit höchster Priorität (niedrigster Index) unter [candidates], oder null. */
        internal fun highestPriorityAmong(candidates: Set<String>): String? =
            candidates
                .minByOrNull { rankOf(it) }
                ?.takeIf { rankOf(it) != Int.MAX_VALUE }
    }
}
