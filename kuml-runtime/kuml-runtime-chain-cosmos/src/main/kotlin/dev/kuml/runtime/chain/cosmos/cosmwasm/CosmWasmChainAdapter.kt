package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.cosmos.Base64Codec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger

/**
 * V3.0.21 — CosmWasm-Implementierung von [KumlChainAdapter].
 *
 * Bindet ein on-chain registriertes kUML-Modell an einen CosmWasm-Smart-Contract:
 * - [connect] liest modelHash/modelUri/schemaVersion via Cosmos-LCD/Tendermint smart-query
 *   (`/cosmwasm.wasm.v1.Query/SmartContractState` bzw. `abci_query`) aus dem Contract-State.
 * - [subscribe] pollt `/block_results` ab dem aktuellen Block (Cold Flow, unendlich) und filtert
 *   `wasm`-Module-Events des Ziel-Contracts.
 * - [replay] liefert historische `wasm`-Events ab fromBlock bis zum aktuellen Kopf (terminiert).
 *
 * Cosmos nutzt Protobuf + JSON über Tendermint-RPC; KEIN SCALE. Contract-Events kommen als
 * `wasm`-Module-Events mit `attributes`-Liste (key/value base64 in Tendermint < v0.38, sonst plain).
 * Der Adapter ermittelt einmalig bei [connect] ob Attribute base64-kodiert sind.
 *
 * Cold-Flow-Garantie: der laufende Block-Cursor ist lokale `flow{}`-Variable, NICHT Instanz-Feld.
 * Thread-Safety: [connect] muss vollständig abgeschlossen sein bevor [subscribe]/[replay].
 *
 * @param urlValidator SSRF-Validierung. Für Tests: [CosmWasmUrlValidator.NoOp] injizieren.
 * @param pollIntervalMillis Poll-Abstand für [subscribe] (Default 2 000 ms).
 * @param pageSize Anzahl Blöcke pro replay-Batch (derzeit ungenutzt, für zukünftige Batching-Impl).
 * @param maxReplayBlocks Maximale Anzahl Blöcke pro replay()-Aufruf (Default 100 000).
 *   Schützt vor unbegrenzten RPC-Aufrufen bei großen Replay-Fenstern.
 * @param clientFactory Factory für [CosmWasmRpcClient] (injizierbar für Tests).
 * @param eventDecoder Decoder für wasm-Events (injizierbar für Tests).
 */
public class CosmWasmChainAdapter(
    private val urlValidator: CosmWasmUrlValidator = CosmWasmUrlValidator.Default,
    private val pollIntervalMillis: Long = 2_000L,
    private val pageSize: Int = 20,
    private val maxReplayBlocks: Long = DEFAULT_MAX_REPLAY_BLOCKS,
    private val clientFactory: (String) -> CosmWasmRpcClient = { url -> CosmWasmRpcClient(url) },
    private val eventDecoder: CosmWasmEventDecoder = CosmWasmEventDecoder(),
) : KumlChainAdapter {
    private var rpcClient: CosmWasmRpcClient? = null
    private var blockClock: CosmWasmBlockClock? = null
    private var contractAddr: String? = null

    /**
     * Stellt Verbindung zu einem CosmWasm-Contract her und liest dessen kUML-Modell-Identität.
     *
     * @param rpcUrl Tendermint-RPC-Endpunkt (z.B. "https://rpc.juno.example:443").
     * @param contractAddress Bech32-Contract-Adresse (z.B. "juno1...").
     * @return [ContractIdentity] mit dem on-chain gespeicherten Modell-Hash und -URI.
     * @throws CosmWasmChainAdapterException.InvalidContractAddress bei ungültigem Bech32.
     * @throws CosmWasmChainAdapterException.InvalidUrlException bei SSRF-Verletzung.
     */
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        urlValidator.validate(rpcUrl)
        if (!CosmosAddress.isValidContract(contractAddress)) {
            throw CosmWasmChainAdapterException.InvalidContractAddress(contractAddress)
        }
        val client = clientFactory(rpcUrl)
        rpcClient = client
        blockClock = CosmWasmBlockClock(client)
        contractAddr = contractAddress

        // Smart-Query { "kuml_identity": {} } gegen den Contract; Resultat ist base64 → JSON.
        val query = "{\"kuml_identity\":{}}"
        val resultJson = client.smartQuery(contractAddress, query)
        val fields =
            resultJson as? JsonObject
                ?: throw CosmWasmChainAdapterException.MalformedResponse("smart query result is not an object")

        val modelHash = decodeModelHash(fields)
        val modelUri =
            fields["model_uri"]?.jsonPrimitive?.content
                ?: throw CosmWasmChainAdapterException.MalformedResponse("smart query result missing 'model_uri'")
        val schemaVersionRaw = fields["schema_version"]?.jsonPrimitive?.content
        val schemaVersion =
            schemaVersionRaw?.toIntOrNull()
                ?: run {
                    if (schemaVersionRaw == null) {
                        LOG.warning("smart query result missing 'schema_version' field — defaulting to 0")
                    } else {
                        LOG.warning("schema_version is not a valid integer — defaulting to 0")
                    }
                    0
                }

        return ContractIdentity(contractAddress, modelHash, modelUri, schemaVersion)
    }

    /**
     * Live-Strom aller `wasm`-Events des Ziel-Contracts ab dem aktuellen Block.
     * Cold Flow: jede Collection startet mit dem aktuellen Block-Kopf.
     * Unendlich — mit `.take(n)` oder Cancel begrenzen.
     */
    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val addr = requireContract()
            var height = client.getLatestBlockHeight() + 1
            while (true) {
                val head = client.getLatestBlockHeight()
                while (height <= head) {
                    emitWasmEvents(client, addr, height)
                    height++
                }
                delay(pollIntervalMillis)
            }
        }

    /**
     * Historischer Replay aller `wasm`-Events des Ziel-Contracts ab [fromBlock] (inklusive)
     * bis zum aktuellen Kopf. Terminiert sobald der Kopf erreicht ist.
     *
     * Um unbegrenzte RPC-Aufrufe und Speicherwachstum zu vermeiden, ist die maximale Replay-
     * Fenstergröße auf [maxReplayBlocks] begrenzt. Bei Überschreitung wird eine
     * [CosmWasmChainAdapterException.ReplayWindowExceeded]-Exception geworfen.
     *
     * @param fromBlock Erster Block (Höhe) ab dem Events eingeschlossen werden. Muss ≥ 0 sein.
     */
    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val addr = requireContract()
            val head = client.getLatestBlockHeight()
            val startHeight = if (fromBlock < 1) 1L else fromBlock
            val windowSize = head - startHeight + 1
            if (windowSize > maxReplayBlocks) {
                throw CosmWasmChainAdapterException.ReplayWindowExceeded(
                    fromBlock = startHeight,
                    toBlock = head,
                    maxBlocks = maxReplayBlocks,
                )
            }
            var height = startHeight
            while (height <= head) {
                emitWasmEvents(client, addr, height)
                height++
            }
        }
    }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    /** Liest block_results für [height], dekodiert wasm-Events des Ziel-Contracts und emittiert sie. */
    private suspend fun FlowCollector<ChainEvent>.emitWasmEvents(
        client: CosmWasmRpcClient,
        addr: String,
        height: Long,
    ) {
        val results = client.getBlockResults(height)
        for (e in eventDecoder.decodeWasmEvents(results, addr, height)) emit(e)
    }

    /**
     * Dekodiert model_hash aus drei möglichen Formen im Smart-Query-Resultat:
     * - JsonArray von Int-Werten: Vec<u8> als Int-Array
     * - JsonPrimitive (Base64-String): häufigste CosmWasm-Form (Binary serde)
     * - JsonPrimitive (Hex-String mit 0x-Präfix)
     */
    private fun decodeModelHash(fields: JsonObject): ByteArray {
        val el =
            fields["model_hash"]
                ?: throw CosmWasmChainAdapterException.MalformedResponse("smart query result missing 'model_hash'")
        return when (el) {
            is JsonArray ->
                ByteArray(el.size) { i ->
                    el[i]
                        .jsonPrimitive.content
                        .toInt()
                        .toByte()
                }
            else -> {
                val s = el.jsonPrimitive.content
                if (s.startsWith("0x") || s.startsWith("0X")) hexToBytes(s) else Base64Codec.decode(s)
            }
        }
    }

    private fun requireClient(): CosmWasmRpcClient = rpcClient ?: error("CosmWasmChainAdapter not connected — call connect() first")

    private fun requireContract(): String = contractAddr ?: error("CosmWasmChainAdapter not connected — call connect() first")

    public companion object {
        private val LOG: Logger = Logger.getLogger(CosmWasmChainAdapter::class.java.name)

        /** Cosmos `wasm`-Module-Event-Typ. */
        public const val WASM_EVENT_TYPE: String = "wasm"

        /**
         * Standard-maximale Replay-Fenstergröße (100 000 Blöcke).
         * Schützt vor unbegrenzten RPC-Aufrufen bei großen Replay-Fenstern.
         * Kann per Konstruktor überschrieben werden.
         */
        public const val DEFAULT_MAX_REPLAY_BLOCKS: Long = 100_000L

        /**
         * Konvertiert einen 0x-Hex-String in ein ByteArray.
         * Wirft [CosmWasmChainAdapterException.MalformedResponse] bei ungültigem Hex.
         */
        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return ByteArray(0)
            if (clean.length % 2 != 0) {
                throw CosmWasmChainAdapterException.MalformedResponse("Hex string has odd length: '$hex'")
            }
            return try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: NumberFormatException) {
                throw CosmWasmChainAdapterException.MalformedResponse("Invalid hex string: '$hex'", e)
            }
        }
    }
}
