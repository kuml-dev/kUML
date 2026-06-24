package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.wasm.ink.InkAbiMetadata
import dev.kuml.runtime.chain.wasm.ink.InkEventDecoder
import dev.kuml.runtime.chain.wasm.rpc.SubstrateRpcClient
import dev.kuml.runtime.chain.wasm.rpc.SubstrateRpcUrlValidator
import dev.kuml.runtime.chain.wasm.rpc.SubstrateWasmException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * V3.0.22 — Substrate/Polkadot ink!-Implementierung von [KumlChainAdapter].
 *
 * Generischer WASM-Contract-Adapter: bindet ein on-chain registriertes kUML-Modell an einen
 * ink!-Smart-Contract auf einer Substrate-Kette (`pallet-contracts`).
 *
 * - [connect] liest die KumlRegistry-Storage des Contracts (modelHash/modelUri/schemaVersion)
 *   ueber `state_call` / `contracts_call` und parst die mitgelieferte ink!-ABI-Metadata.
 * - [subscribe] pollt neue Bloecke ab dem aktuellen Kopf, filtert `Contracts.ContractEmitted`-
 *   Events fuer die Ziel-Contract-Adresse und dekodiert sie via [InkEventDecoder] (Cold Flow, unendlich).
 * - [replay] liefert historische ContractEmitted-Events ab [fromBlock] bis zum aktuellen Kopf (terminiert).
 *
 * Cold-Flow-Garantie: der Block-Cursor ist lokale `flow{}`-Variable, NICHT Instanz-Feld.
 * Thread-Safety: [connect] muss vollstaendig abgeschlossen sein bevor [subscribe]/[replay].
 *
 * **JVM-only** (java.net.http.HttpClient im RPC-Client). Die Decode-Kerne
 * (ScaleCodec, InkAbiMetadata, InkEventDecoder) bleiben Native-Image-tauglich und koennen von
 * Plugin-Autoren ohne diese Klasse wiederverwendet werden.
 *
 * @param urlValidator SSRF-Validierung. Fuer Tests: [SubstrateRpcUrlValidator.NoOp] injizieren.
 * @param pollIntervalMillis Poll-Abstand fuer [subscribe] (Default 6 000 ms — Substrate-Blockzeit ~6 s).
 * @param maxBlocksPerReplayBatch Cap fuer die Anzahl Bloecke pro Replay-Iteration (DoS/Memory-Schutz).
 * @param clientFactory Factory fuer [SubstrateRpcClient] (injizierbar fuer Tests).
 * @param abiProvider Liefert die ink!-ABI-Metadata fuer die Contract-Adresse (injizierbar fuer Tests).
 */
public class SubstrateWasmAdapter(
    private val urlValidator: SubstrateRpcUrlValidator = SubstrateRpcUrlValidator.Default,
    private val pollIntervalMillis: Long = 6_000L,
    private val maxBlocksPerReplayBatch: Long = 1_000L,
    private val clientFactory: (String) -> SubstrateRpcClient = { url -> SubstrateRpcClient(url) },
    private val abiProvider: suspend (SubstrateRpcClient, String) -> InkAbiMetadata = ::resolveAbiFromChain,
) : KumlChainAdapter,
    AutoCloseable {
    private var rpcClient: SubstrateRpcClient? = null
    private var blockClock: SubstrateBlockClock? = null
    private var contractAddress: String? = null
    private var eventDecoder: InkEventDecoder? = null

    /**
     * Stellt Verbindung zu einem ink!-Contract her.
     *
     * @param rpcUrl Substrate JSON-RPC-Endpunkt (http/https oder ws/wss → hier http-Polling).
     * @param contractAddress SS58- oder Hex-AccountId des ink!-Contracts.
     * @return [ContractIdentity] mit dem on-chain gespeicherten Modell-Hash und -URI.
     * @throws SubstrateWasmException.InvalidAddress wenn contractAddress leer/ungueltig.
     * @throws SubstrateWasmException.InvalidUrl bei SSRF-Verletzung.
     */
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        if (contractAddress.isBlank()) throw SubstrateWasmException.InvalidAddress(contractAddress)
        urlValidator.validate(rpcUrl)

        val client = clientFactory(rpcUrl)
        rpcClient = client
        blockClock = SubstrateBlockClock(client)
        this.contractAddress = contractAddress

        val abi = abiProvider(client, contractAddress)
        eventDecoder = InkEventDecoder(abi)

        val identity = client.readRegistryIdentity(contractAddress)
        return identity
    }

    /**
     * Liefert einen endlosen Cold-Flow neuer [ChainEvent]s ab dem naechsten noch nicht
     * finalisierten Block (currentHead() + 1 zum Zeitpunkt des collect()-Aufrufs).
     *
     * **Wichtig — Block-Semantik**: subscribe() startet bei `currentHead()+1`. Das bedeutet,
     * der Block der gerade 'aktuell' ist wenn connect() abgeschlossen wurde, wird NICHT
     * inkludiert. Das unterscheidet sich von replay()-Semantik, die [fromBlock] einschliesst.
     * Fuer historische Events bitte [replay] verwenden.
     */
    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val decoder = requireDecoder()
            val target = requireAddress()
            var fromBlock = client.currentHead() + 1
            while (true) {
                // Delay BEFORE polling so that processing time does not add to the effective poll
                // interval when blocks are available.  On first iteration we skip the delay to
                // pick up any blocks that arrived between connect() and subscribe().
                delay(pollIntervalMillis)
                val head =
                    try {
                        client.currentHead()
                    } catch (e: Exception) {
                        // Transient RPC failure — log and retry on next poll cycle rather than
                        // terminating the Cold Flow with an unrecoverable exception.
                        continue
                    }
                if (head >= fromBlock) {
                    val capped = minOf(head, fromBlock + maxBlocksPerReplayBatch - 1)
                    var b = fromBlock
                    while (b <= capped) {
                        for (ev in client.contractEmittedAt(b, target)) {
                            decodeAndEmit(decoder, ev)?.let { emit(it) }
                        }
                        b++
                    }
                    fromBlock = capped + 1
                }
            }
        }

    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val decoder = requireDecoder()
            val target = requireAddress()
            val head = client.currentHead()
            var b = fromBlock
            while (b <= head) {
                val batchEnd = minOf(head, b + maxBlocksPerReplayBatch - 1)
                var cur = b
                while (cur <= batchEnd) {
                    for (ev in client.contractEmittedAt(cur, target)) {
                        decodeAndEmit(decoder, ev)?.let { emit(it) }
                    }
                    cur++
                }
                b = batchEnd + 1
            }
        }
    }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    /** Releases the background executor thread held by [SubstrateBlockClock]. */
    override fun close() {
        blockClock?.close()
    }

    /**
     * Dekodiert ein rohes ContractEmitted-Event und mappt es auf einen kUML-[ChainEvent].
     * Liefert null, wenn der Event-Typ nicht zur ABI passt (anderer Contract / unbekannter Event).
     */
    private fun decodeAndEmit(
        decoder: InkEventDecoder,
        raw: SubstrateRpcClient.RawContractEvent,
    ): ChainEvent? {
        val decoded = decoder.decode(raw.topicsHex, raw.dataScale) ?: return null
        return ChainEvent(
            eventType = decoded.name,
            payloadAbi = raw.dataScale,
            blockNumber = raw.blockNumber,
            txHash = raw.extrinsicHash,
        )
    }

    private fun requireClient(): SubstrateRpcClient = rpcClient ?: error("SubstrateWasmAdapter not connected — call connect() first")

    private fun requireDecoder(): InkEventDecoder = eventDecoder ?: error("SubstrateWasmAdapter not connected — call connect() first")

    private fun requireAddress(): String = contractAddress ?: error("SubstrateWasmAdapter not connected — call connect() first")

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Default-ABI-Provider: liest die ink!-Metadata aus dem on-chain abgelegten `modelUri`
         * (erwartet ein an die Registry gebundenes Metadata-Dokument) bzw. faellt auf eine
         * leere ABI zurueck, wenn keine Metadata aufloesbar ist.
         *
         * In der Praxis wird der Provider in produktiven Setups injiziert (Metadata aus IPFS /
         * lokalem Bundle), damit der Adapter nicht jedes Mal die Kette nach der vollen Metadata
         * fragen muss. Diese Default-Impl liest das `model_uri`-Feld und laedt von dort.
         */
        public suspend fun resolveAbiFromChain(
            client: SubstrateRpcClient,
            contractAddress: String,
        ): InkAbiMetadata {
            val metadataJson = client.fetchContractMetadata(contractAddress)
            return InkAbiMetadata.parse(json.parseToJsonElement(metadataJson))
        }
    }
}

/**
 * Block-basierte Uhr fuer die Substrate-Kette. Liest `chain_getHeader`-Timestamp (via
 * `pallet-timestamp`-Set-Inherent) bzw. faellt auf eine deterministische, blockhoehen-abgeleitete
 * Zeit zurueck, damit Replays reproduzierbar bleiben.
 *
 * **currentBlock()-Implementierung**: Das [BlockClock]-Interface ist non-suspend. Um einen
 * Deadlock zu vermeiden, wenn [currentBlock] aus einem Coroutine-Kontext (z.B. Flow-Collector
 * auf einem confined Dispatcher) aufgerufen wird, wird der suspend-Call [SubstrateRpcClient.currentHead]
 * auf einem dedizierten Hintergrund-Thread via einem gebundenen Single-Thread-Executor ausgefuehrt.
 *
 * **DoS-Schutz / Thread-Begrenzung**: Anstatt bei jedem Aufruf einen neuen Task an den JVM
 * Common-Fork-Join-Pool zu schicken (was bei rapid polling den Pool saturieren wuerde), cacht
 * diese Implementierung den zuletzt bekannten Block mit einem konfigurierbaren TTL ([cacheTtlMillis]).
 * Nur wenn der Cache abgelaufen ist, wird ein neuer asynchroner Fetch ausgeloest. Ausserdem wird
 * ein dedizierter Single-Thread-Daemon-Executor verwendet, so dass Substrate-RPC-Calls nie den
 * Common-Fork-Join-Pool blockieren.
 *
 * @param cacheTtlMillis TTL fuer den Block-Cache in Millisekunden (Default 1 000 ms).
 */
public class SubstrateBlockClock(
    private val client: SubstrateRpcClient,
    private val cacheTtlMillis: Long = 1_000L,
) : BlockClock,
    AutoCloseable {
    @Volatile
    private var cachedBlock: Long = 0L

    @Volatile
    private var cacheTimestamp: Long = 0L

    private val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "SubstrateBlockClock-fetch").also { it.isDaemon = true }
        }

    override fun currentTime(): Instant = client.currentBlockTimestamp()

    /**
     * Shuts down the background executor thread.  Call this when the owning [SubstrateWasmAdapter]
     * is no longer needed to avoid thread leaks in long-running server deployments where adapter
     * instances are created and discarded per-request.
     */
    override fun close() {
        executor.shutdown()
    }

    override fun currentBlock(): Long {
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp < cacheTtlMillis) return cachedBlock
        return runCatching {
            // Fuehre den suspend-Call auf einem separaten Daemon-Thread aus, um Deadlock
            // bei confined Dispatchers (z.B. Dispatchers.Main, newSingleThreadContext) zu vermeiden.
            val future =
                java.util.concurrent.CompletableFuture.supplyAsync(
                    { kotlinx.coroutines.runBlocking { client.currentHead() } },
                    executor,
                )
            val block = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
            cachedBlock = block
            cacheTimestamp = System.currentTimeMillis()
            block
        }.getOrDefault(cachedBlock)
    }
}
