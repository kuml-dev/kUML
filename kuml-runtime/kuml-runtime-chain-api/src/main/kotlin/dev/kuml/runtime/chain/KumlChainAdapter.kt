package dev.kuml.runtime.chain

import kotlinx.coroutines.flow.Flow

/**
 * V3.0.1 — Engine-agnostische Adapter-Schnittstelle für die Anbindung eines
 * kUML-Behaviour-Modells an eine Blockchain bzw. einen Distributed-Ledger.
 *
 * Bewusst **pure Kotlin / Native-Image-tauglich**: keine konkrete Web3-/RPC-Library
 * im API-Modul. Konkrete Adapter (z.B. ein EVM-/Solana-Backend) leben in separaten
 * Implementierungsmodulen und werden über diese Schnittstelle eingebunden.
 *
 * Implementierungs-Contract:
 * - [connect] ist suspending und stellt die Verbindung her; Fehler werden als Exceptions geworfen.
 * - [subscribe] ist ein Cold Flow: jede Collection startet ein frisches Abonnement.
 * - [replay] ist ein Cold Flow der terminiert sobald der aktuelle Block erreicht ist.
 *
 * @see ModelHasher für die kanonische Hash-Berechnung über Modell-Quelltext.
 * @see ChainEvent für die Event-Struktur.
 * @see ContractIdentity für die Contract-Identität nach [connect].
 */
public interface KumlChainAdapter {
    /**
     * Stellt die Verbindung zu einem Smart Contract her und liefert dessen Identität.
     *
     * @param rpcUrl JSON-RPC- bzw. Node-Endpunkt (z.B. `https://mainnet.infura.io/v3/…`).
     * @param contractAddress On-Chain-Adresse des Vertrags (Hex, chain-spezifisches Format).
     * @return [ContractIdentity] mit dem on-chain gespeicherten Modell-Hash und -URI.
     * @throws IllegalArgumentException wenn [rpcUrl] oder [contractAddress] leer sind.
     */
    public suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity

    /**
     * Live-Strom aller eingehenden On-Chain-Events ab dem aktuellen Block.
     *
     * Cold Flow: jede Collection startet ein frisches Abonnement.
     * Der Flow ist unendlich — Aufrufer müssen ihn aktiv canceln oder
     * über Operatoren wie `take(n)` begrenzen.
     */
    public fun subscribe(): Flow<ChainEvent>

    /** Block-basierte Uhr für deterministische, ledger-gebundene Zeitstempel. */
    public fun blockClock(): BlockClock

    /**
     * Historischer Replay aller Events ab [fromBlock] (inklusive) bis zum aktuellen
     * Block. Im Gegensatz zu [subscribe] terminiert dieser Flow sobald der aktuelle
     * Kopf erreicht ist.
     *
     * @param fromBlock Erster Block, ab dem Events eingeschlossen werden (inklusive).
     *   Muss ≥ 0 sein.
     */
    public fun replay(fromBlock: Long): Flow<ChainEvent>
}
