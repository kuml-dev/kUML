package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicLong

/**
 * V3.0.21 — Aggregiert mehrere [KumlChainAdapter] (je eine Chain) zu einem einzigen
 * deterministischen Event-Strom.
 *
 * Implementiert bewusst NICHT [KumlChainAdapter]: connect() dort hat Signatur
 * (rpcUrl, contractAddress) → ContractIdentity; hier braucht connectAll() ein
 * chainId-indiziertes Endpoint-Mapping → MultiChainContractIdentity. Eigenständige Klasse.
 *
 * @property adapters chainId → Adapter. Iterationsreihenfolge bestimmt Primary-Chain
 *   (sofern kein PriorityChain-Resolver). Aufrufer übergeben idealerweise eine LinkedHashMap.
 *
 * **Sicherheitshinweis — Eclipse-Angriff:** Die Korrektheit des [ConflictResolver] setzt
 * voraus, dass die **Mehrheit** der konfigurierten Chains ehrlich ist. Ein Angreifer, der
 * ≥ ceil(n/2) der Adapter-Chains kontrolliert, kann den ConflictResolver durch koordinierte
 * Blockproduktion oder gefälschte RPC-Antworten manipulieren und den deterministischen
 * Event-Strom vergiften. Nutzer mit sicherheitskritischen Anforderungen sollten:
 * (a) ausschließlich vertrauenswürdige RPC-Endpoints verwenden,
 * (b) die Anzahl der Chains aus einer einzigen nicht-vertrauenswürdigen Quelle minimieren,
 * (c) [MultiChainContractIdentity.consistent] = false von [connectAll] als Warnsignal
 *     behandeln und bei false keine sicherheitskritischen Entscheidungen treffen.
 */
public class MultiChainAdapter(
    private val adapters: Map<String, KumlChainAdapter>,
    private val conflictResolver: ConflictResolver = ConflictResolver.EarliestBlock,
) {
    init {
        require(adapters.isNotEmpty()) { "MultiChainAdapter erfordert mindestens einen Adapter" }
    }

    public companion object {
        /**
         * Zwei Events gelten als Konflikt-Kandidaten, wenn ihre blockNumber-Differenz
         * |a.block − b.block| ≤ [CONFLICT_WINDOW_BLOCKS] ist (bei gleichem eventType,
         * verschiedener Chain). Kleines Fenster fängt Cross-Chain-Block-Drift ab,
         * ohne unverwandte spätere Events fälschlich zu mergen.
         */
        public const val CONFLICT_WINDOW_BLOCKS: Long = 2L

        /**
         * Standard-Obergrenze für die Gesamtzahl historischer Events in [replayAll].
         * Schützt vor OOM bei bösartigen oder fehlkonfigurierten Chains mit sehr langer
         * Historie (insbesondere bei fromBlock=0 auf produktiven Chains).
         * Aufrufer können das Limit per `maxEvents`-Parameter explizit überschreiben.
         */
        public const val DEFAULT_MAX_REPLAY_EVENTS: Long = 100_000L
    }

    /**
     * Verbindet alle Adapter PARALLEL und aggregiert ihre Identitäten.
     *
     * @param endpoints chainId → (rpcUrl, contractAddress).
     * @throws IllegalArgumentException wenn endpoints nicht exakt dieselben chainIds
     *   wie [adapters] abdeckt (sonst stille Teil-Verbindung).
     */
    public suspend fun connectAll(endpoints: Map<String, Pair<String, String>>): MultiChainContractIdentity =
        coroutineScope {
            require(endpoints.keys == adapters.keys) {
                "endpoints-Keys ${endpoints.keys} müssen adapters-Keys ${adapters.keys} entsprechen"
            }
            // Reihenfolge-stabil über adapters (LinkedHashMap-Iteration), parallel via async.
            val deferred =
                adapters.map { (chainId, adapter) ->
                    val (rpcUrl, address) = endpoints.getValue(chainId)
                    chainId to async { adapter.connect(rpcUrl, address) }
                }
            // LinkedHashMap → Primary-Chain = erster Adapter-Eintrag, deterministisch.
            val identities = LinkedHashMap<String, ContractIdentity>(deferred.size)
            for ((chainId, d) in deferred) {
                identities[chainId] = d.await()
            }
            MultiChainContractIdentity.from(identities)
        }

    /**
     * Live-Merge aller subscribe()-Flows. Cold Flow.
     *
     * - Jedes Event bekommt eine monoton steigende globalSequence (pro Collection
     *   frisch ab 0 — ein neuer AtomicLong je flow{}-Aufruf, damit der Flow cold bleibt).
     * - Konflikt-Erkennung: ein gleitendes Fenster der zuletzt emittierten Events.
     *   Trifft ein neues Event auf ein Fenster-Event aus ANDERER Chain mit gleichem
     *   eventType und |Δblock| ≤ [CONFLICT_WINDOW_BLOCKS] → conflictResolver entscheidet.
     *
     * **Konfliktauflösungs-Semantik (push-only Flow):**
     * Da emittierte Events nicht rückwirkend zurückgezogen werden können, werden bei
     * einem erkannten Konflikt BEIDE Events emittiert — der bereits durchgelassene rival
     * erhält nachträglich [ConflictRole.LOSER], der neue Gewinner [ConflictRole.WINNER].
     * Downstream-Consumer sollen Events mit [ConflictRole.LOSER] verwerfen.
     *
     * Kein Konflikt → [ConflictRole.NONE].
     * Erkannter Konflikt, Gewinner = bereits emittiertes rival → candidate verworfen (kein emit).
     * Erkannter Konflikt, Gewinner = candidate → rival bekommt LOSER-Korrektur-Event,
     * candidate wird als WINNER emittiert, Fenster wird auf candidate aktualisiert.
     */
    public fun subscribeAll(): Flow<MergedChainEvent> =
        flow {
            val seq = AtomicLong(0L) // pro Collection frisch → Flow bleibt cold
            // tagged: chainId an jedes ChainEvent heften, bevor gemerged wird.
            val tagged: List<Flow<MergedChainEvent>> =
                adapters.map { (chainId, adapter) ->
                    adapter.subscribe().map { ev ->
                        MergedChainEvent(chainId, seq.getAndIncrement(), ev)
                    }
                }
            // Konflikt-Fenster: zuletzt durchgelassene Events (klein halten).
            val recent = ArrayDeque<MergedChainEvent>()
            tagged.merge().collect { candidate ->
                val rival = recent.firstOrNull { existing -> isConflict(existing, candidate) }
                if (rival == null) {
                    emit(candidate)
                    pushRecent(recent, candidate)
                } else {
                    val winner = conflictResolver.resolve(rival, candidate)
                    if (winner !== rival) {
                        // candidate gewinnt: rival war bereits emittiert (nicht rücknehmbar).
                        // Korrektur: LOSER-Tombstone für rival emittieren, damit Downstream
                        // das ursprüngliche rival-Event als überschrieben erkennt.
                        // Dann candidate als WINNER emittieren und Fenster aktualisieren.
                        emit(MergedChainEvent(rival.chainId, rival.globalSequence, rival.originalEvent, ConflictRole.LOSER))
                        emit(MergedChainEvent(candidate.chainId, candidate.globalSequence, candidate.originalEvent, ConflictRole.WINNER))
                        replaceRecent(recent, rival, candidate)
                    }
                    // winner === rival → candidate ist Verlierer → still verwerfen (kein emit).
                }
            }
        }

    /**
     * Historischer Merge aller replay()-Flows, deterministisch sortiert nach
     * (blockNumber ↑, chainId ↑, txHash ↑). Terminiert, wenn ALLE Chain-Replays fertig.
     *
     * Da replay()-Flows endlich sind, werden sie pro Chain vollständig gesammelt,
     * dann global stabil sortiert. Sequenz wird NACH dem Sortieren vergeben → der
     * globalSequence-Wert entspricht exakt der deterministischen Ausgabe-Position.
     *
     * **DoS-Schutz:** Die Gesamtzahl der gesammelten Events über alle Chains ist auf
     * [maxEvents] begrenzt. Wird dieses Limit überschritten, wirft die Methode eine
     * [IllegalStateException] mit klarer Fehlermeldung, bevor OOM eintreten kann.
     * Standard-Cap: [DEFAULT_MAX_REPLAY_EVENTS] (100 000). Aufrufer, die größere
     * Historien benötigen, müssen [maxEvents] explizit anheben — und sicherstellen,
     * dass der verfügbare Heap ausreicht. `fromBlock=0` auf produktiven Chains mit
     * Millionen historischer Events ist ein realisierbarer OOM-Angriffspfad; Aufrufer
     * sollten [fromBlock] immer so wählen, dass die erwartete Ergebnismenge begrenzt bleibt.
     *
     * @param fromBlock erstes Block, ab dem Events abgerufen werden.
     * @param maxEvents maximale Gesamtzahl Events über alle Chains (Default: [DEFAULT_MAX_REPLAY_EVENTS]).
     * @throws IllegalStateException wenn die Gesamtzahl gesammelter Events [maxEvents] überschreitet.
     */
    public fun replayAll(
        fromBlock: Long,
        maxEvents: Long = DEFAULT_MAX_REPLAY_EVENTS,
    ): Flow<MergedChainEvent> =
        flow {
            // 1. Pro Chain vollständig sammeln — PARALLEL (alle Chains gleichzeitig),
            //    damit die Gesamtlatenz dem Maximum der einzelnen Replay-Zeiten entspricht
            //    statt deren Summe. Spiegelt das parallel-async-Muster von connectAll().
            val collected = ArrayList<Pair<String, ChainEvent>>()
            coroutineScope {
                adapters
                    .map { (chainId, adapter) -> chainId to async { adapter.replay(fromBlock).toList() } }
                    .forEach { (chainId, deferred) ->
                        for (ev in deferred.await()) {
                            if (collected.size.toLong() >= maxEvents) {
                                throw IllegalStateException(
                                    "replayAll() hat das Event-Limit von $maxEvents überschritten " +
                                        "(fromBlock=$fromBlock, Chains=${adapters.keys}). " +
                                        "fromBlock erhöhen oder maxEvents explizit anheben.",
                                )
                            }
                            collected += chainId to ev
                        }
                    }
            }
            // 2. Global stabil sortieren: block ↑, dann chainId ↑, dann txHash ↑.
            //    txHash als finaler Tie-Break → totale Ordnung, reproduzierbar.
            val sorted =
                collected.sortedWith(
                    compareBy({ it.second.blockNumber }, { it.first }, { it.second.txHash }),
                )
            // 3. Sequenz NACH Sortierung vergeben → globalSequence == Ausgabe-Position.
            var s = 0L
            for ((chainId, ev) in sorted) {
                emit(MergedChainEvent(chainId, s++, ev))
            }
        }

    /**
     * Blockuhr der Primary-Chain. Bei PriorityChain-Resolver: Chain mit höchster
     * Priorität (= niedrigster Index in der priority-Liste, sofern unter adapters);
     * sonst erster Adapter-Eintrag.
     */
    public fun primaryBlockClock(): BlockClock {
        val primaryId =
            if (conflictResolver is ConflictResolver.PriorityChain) {
                conflictResolver.highestPriorityAmong(adapters.keys) ?: adapters.keys.first()
            } else {
                adapters.keys.first()
            }
        return adapters.getValue(primaryId).blockClock()
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    private fun isConflict(
        a: MergedChainEvent,
        b: MergedChainEvent,
    ): Boolean =
        a.chainId != b.chainId &&
            a.eventType == b.eventType &&
            kotlin.math.abs(a.blockNumber - b.blockNumber) <= CONFLICT_WINDOW_BLOCKS

    private fun pushRecent(
        recent: ArrayDeque<MergedChainEvent>,
        ev: MergedChainEvent,
    ) {
        recent.addFirst(ev)
        while (recent.size > RECENT_WINDOW_SIZE) recent.removeLast()
    }

    private fun replaceRecent(
        recent: ArrayDeque<MergedChainEvent>,
        old: MergedChainEvent,
        new: MergedChainEvent,
    ) {
        recent.remove(old)
        pushRecent(recent, new)
    }
}

/**
 * Größe des gleitenden Konflikt-Erkennungs-Fensters in [MultiChainAdapter.subscribeAll].
 *
 * Warum 16: Das Fenster muss lang genug sein, damit Events aus Chain B noch mit
 * Events aus Chain A verglichen werden können, die kurz zuvor ankamen. Bei
 * [MultiChainAdapter.CONFLICT_WINDOW_BLOCKS] = 2 bedeutet das, dass eine Chain
 * innerhalb von 2 Blöcken höchstens so viele Events produzieren kann, wie das Fenster
 * groß ist. 16 deckt realistisch bis zu 8 parallel beobachtete Chains × 2 Events
 * pro Konflikt-Fenster ab. Wenn Chain A schneller als 16 Events in den 2-Block-
 * Fenster produziert, bevor Chain B's konfligierendes Event ankommt, fällt das rival
 * aus dem Fenster und der Konflikt wird lautlos verpasst.
 *
 * Anpassen, wenn mehr als 8 Chains gleichzeitig beobachtet werden oder wenn der
 * Throughput einer einzelnen Chain regelmäßig >2 Events pro Block überschreitet.
 */
private const val RECENT_WINDOW_SIZE = 16
