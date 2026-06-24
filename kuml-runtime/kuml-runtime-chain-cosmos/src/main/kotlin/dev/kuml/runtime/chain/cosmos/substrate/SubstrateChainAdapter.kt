package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.util.logging.Logger

/**
 * V3.0.21 — Substrate/ink!-Implementierung von [KumlChainAdapter].
 *
 * Bindet ein on-chain registriertes kUML-Modell an einen ink!-Smart-Contract auf einer
 * Substrate-Chain (Polkadot/Kusama/parachains):
 * - [connect] ruft `contracts_call` (RPC) bzw. `state_call` mit einem Read-Message-Selector
 *   auf und dekodiert das SCALE-Resultat (modelHash/modelUri/schemaVersion).
 * - [subscribe] pollt `chain_getBlockHash` + `state_getStorage(System.Events)` ab Kopf
 *   (Cold Flow, unendlich) und filtert `Contracts.ContractEmitted`-Events des Ziel-Contracts.
 * - [replay] iteriert Blöcke ab fromBlock bis zum Kopf und liefert ContractEmitted-Events (terminiert).
 *
 * Substrate nutzt SCALE-Encoding für Events. Der inline [ScaleReader] dekodiert nur die
 * minimal nötigen Felder (compact length-prefix + Bytes) der ContractEmitted-Payload;
 * KEIN vollständiger SCALE-/Metadata-Parser. Die rohe payload landet als `payloadAbi`.
 *
 * Cold-Flow-Garantie: Block-Cursor ist lokale `flow{}`-Variable, NICHT Instanz-Feld.
 *
 * @param urlValidator SSRF-Validierung. Für Tests: [SubstrateUrlValidator.NoOp] injizieren.
 * @param pollIntervalMillis Poll-Abstand für [subscribe] (Default 6 000 ms; Substrate ~6s Blockzeit).
 * @param maxReplayBlocks Maximale Anzahl Blöcke pro replay()-Aufruf (Default 100 000).
 *   Schützt vor unbegrenzten RPC-Aufrufen bei großen Replay-Fenstern.
 * @param clientFactory Factory für [SubstrateRpcClient] (injizierbar für Tests).
 * @param eventDecoder Decoder für ContractEmitted-Events (injizierbar für Tests).
 */
public class SubstrateChainAdapter(
    private val urlValidator: SubstrateUrlValidator = SubstrateUrlValidator.Default,
    private val pollIntervalMillis: Long = 6_000L,
    private val maxReplayBlocks: Long = DEFAULT_MAX_REPLAY_BLOCKS,
    private val clientFactory: (String) -> SubstrateRpcClient = { url -> SubstrateRpcClient(url) },
    private val eventDecoder: SubstrateEventDecoder = SubstrateEventDecoder(),
) : KumlChainAdapter {
    private var rpcClient: SubstrateRpcClient? = null
    private var blockClock: SubstrateBlockClock? = null
    private var contractAddr: String? = null

    /**
     * Stellt Verbindung zu einem ink!-Contract her und liest dessen kUML-Modell-Identität.
     *
     * @param rpcUrl Substrate-JSON-RPC-Endpunkt (https oder wss).
     * @param contractAddress SS58-Contract-Adresse (z.B. "5GrwvaEF...").
     * @return [ContractIdentity] mit dem on-chain gespeicherten Modell-Hash und -URI.
     * @throws SubstrateChainAdapterException.InvalidContractAddress bei ungültigem SS58.
     * @throws SubstrateChainAdapterException.InvalidUrlException bei SSRF-Verletzung.
     */
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        urlValidator.validate(rpcUrl)
        if (!SubstrateAddress.isValid(contractAddress)) {
            throw SubstrateChainAdapterException.InvalidContractAddress(contractAddress)
        }
        val client = clientFactory(rpcUrl)
        rpcClient = client
        blockClock = SubstrateBlockClock(client)
        contractAddr = contractAddress

        // Warn if pallet/variant indices are still at their defaults.
        // The defaults (palletIdx=40, variantIdx=8) are for standard Substrate runtimes only.
        // Real parachains differ: Astar=70, Aleph Zero=33, etc. Without fetching Runtime Metadata
        // at connect() time, the adapter cannot verify the correct indices. Callers should pass
        // the correct indices via SubstrateEventDecoder(contractsPalletIdx=..., ...) to avoid
        // silently emitting no events on non-standard chains.
        if (eventDecoder.contractsPalletIdxIsDefault() && eventDecoder.contractEmittedVariantIdxIsDefault()) {
            LOG.warning(
                "Using default pallet index (${SubstrateEventDecoder.CONTRACTS_PALLET_IDX}) " +
                    "and variant index (${SubstrateEventDecoder.CONTRACT_EMITTED_VARIANT_IDX}) for Contracts.ContractEmitted. " +
                    "These defaults apply to standard Substrate runtimes only. " +
                    "For Astar, Aleph Zero, or other parachains, provide the correct indices via SubstrateEventDecoder " +
                    "constructor arguments — otherwise no events will be emitted.",
            )
        }

        // Read-only ink!-Message "kuml_identity" — Selector als hex an contracts_call.
        val resultHex = client.contractsCall(contractAddress, KUML_IDENTITY_SELECTOR)
        val identity = eventDecoder.decodeIdentity(resultHex)
        return ContractIdentity(
            contractAddress,
            identity.modelHash,
            identity.modelUri,
            identity.schemaVersion,
        )
    }

    /**
     * Live-Strom aller `Contracts.ContractEmitted`-Events des Ziel-Contracts ab dem aktuellen Block.
     * Cold Flow: jede Collection startet am aktuellen Block-Kopf.
     * Unendlich — mit `.take(n)` oder Cancel begrenzen.
     */
    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val addr = requireContract()
            var height = client.getFinalizedHeight() + 1
            while (true) {
                val head = client.getFinalizedHeight()
                while (height <= head) {
                    emitContractEvents(client, addr, height)
                    height++
                }
                delay(pollIntervalMillis)
            }
        }

    /**
     * Historischer Replay aller ContractEmitted-Events des Ziel-Contracts ab [fromBlock]
     * (inklusive) bis zum aktuellen finalisierten Kopf. Terminiert am Kopf.
     *
     * Die Replay-Fenstergröße ist auf [maxReplayBlocks] begrenzt. Bei Überschreitung wird
     * eine [SubstrateChainAdapterException.ReplayWindowExceeded]-Exception geworfen.
     *
     * @param fromBlock Erste Block-Höhe (inklusive). Muss ≥ 0 sein.
     */
    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val addr = requireContract()
            val head = client.getFinalizedHeight()
            val startHeight = if (fromBlock < 1) 1L else fromBlock
            val windowSize = head - startHeight + 1
            if (windowSize > maxReplayBlocks) {
                throw SubstrateChainAdapterException.ReplayWindowExceeded(
                    fromBlock = startHeight,
                    toBlock = head,
                    maxBlocks = maxReplayBlocks,
                )
            }
            var height = startHeight
            while (height <= head) {
                emitContractEvents(client, addr, height)
                height++
            }
        }
    }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    /** Liest System.Events des Blocks [height], dekodiert ContractEmitted und emittiert. */
    private suspend fun FlowCollector<ChainEvent>.emitContractEvents(
        client: SubstrateRpcClient,
        addr: String,
        height: Long,
    ) {
        val blockHash = client.getBlockHash(height)
        val eventsHex = client.getSystemEvents(blockHash)
        for (e in eventDecoder.decodeContractEmitted(eventsHex, addr, height, blockHash)) emit(e)
    }

    private fun requireClient(): SubstrateRpcClient = rpcClient ?: error("SubstrateChainAdapter not connected — call connect() first")

    private fun requireContract(): String = contractAddr ?: error("SubstrateChainAdapter not connected — call connect() first")

    public companion object {
        private val LOG: Logger = Logger.getLogger(SubstrateChainAdapter::class.java.name)

        /** Substrate-Event-Pallet+Variante für ink!-Contract-Events. */
        public const val CONTRACT_EMITTED: String = "Contracts.ContractEmitted"

        /**
         * Standard-maximale Replay-Fenstergröße (100 000 Blöcke).
         * Kann per Konstruktor überschrieben werden.
         */
        public const val DEFAULT_MAX_REPLAY_BLOCKS: Long = 100_000L

        /**
         * 4-Byte-ink!-Message-Selector für die `kuml_identity`-Read-Message.
         * Default = blake2b("kuml_identity")[0..4] (Platzhalter — bei realem Contract anpassen).
         */
        public const val KUML_IDENTITY_SELECTOR: String = "0x9bae9d5e"
    }
}
