package dev.kuml.runtime.chain.move.sui

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.move.Base64Decoder
import dev.kuml.runtime.chain.move.MoveAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.0.20 — Sui-Implementierung von [KumlChainAdapter].
 *
 * Bindet ein on-chain registriertes kUML-Modell an ein Sui-Objekt (KumlRegistry):
 * - [connect] liest modelHash/modelUri/schemaVersion via sui_getObject aus dem Objekt.
 * - [subscribe] pollt `suix_queryEvents` ab cursor=null (Cold Flow, unendlich).
 * - [replay] liefert historische Events ab fromBlock (checkpoint) bis Ende (terminiert).
 *
 * Cold-Flow-Garantie: Cursor-State ist lokale `flow{}`-Variable, NICHT Instanz-Feld.
 * Thread-Safety: [connect] muss vollständig abgeschlossen sein bevor [subscribe]/[replay].
 *
 * @param urlValidator SSRF-Validierung. Für Tests: [SuiRpcUrlValidator.NoOp] injizieren.
 * @param pollIntervalMillis Poll-Abstand für [subscribe] (Default 2 000 ms).
 * @param pageLimit Events pro queryEvents-Page.
 * @param clientFactory Factory für [SuiRpcClient] (injizierbar für Tests).
 * @param eventDecoder Decoder für Event-Objekte (injizierbar für Tests).
 */
public class SuiChainAdapter(
    private val urlValidator: SuiRpcUrlValidator = SuiRpcUrlValidator.Default,
    private val pollIntervalMillis: Long = 2_000L,
    private val pageLimit: Int = 50,
    private val clientFactory: (String) -> SuiRpcClient = { url -> SuiRpcClient(url) },
    private val eventDecoder: SuiEventDecoder = SuiEventDecoder(),
) : KumlChainAdapter {
    private var rpcClient: SuiRpcClient? = null
    private var blockClock: SuiBlockClock? = null
    private var objectId: String? = null
    private var packageId: String? = null
    private var module: String = DEFAULT_MODULE

    /**
     * Stellt Verbindung zu einem Sui-KumlRegistry-Objekt her.
     *
     * @param rpcUrl Sui JSON-RPC-Endpunkt.
     * @param contractAddress Object-ID des KumlRegistry-Objekts (0x + 1..64 hex).
     * @return [ContractIdentity] mit dem on-chain gespeicherten Modell-Hash und -URI.
     * @throws SuiChainAdapterException.InvalidObjectId wenn contractAddress kein gültiges Format.
     * @throws SuiChainAdapterException.InvalidUrlException bei SSRF-Verletzung.
     */
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        urlValidator.validate(rpcUrl)
        if (!MoveAddress.isValid(contractAddress)) {
            throw SuiChainAdapterException.InvalidObjectId(contractAddress)
        }
        val client = clientFactory(rpcUrl)
        rpcClient = client
        blockClock = SuiBlockClock(client)
        objectId = contractAddress

        val result = client.getObject(contractAddress).jsonObject
        val data =
            result["data"]?.jsonObject
                ?: throw SuiChainAdapterException.MalformedResponse("getObject result missing 'data'")
        val content =
            data["content"]?.jsonObject
                ?: throw SuiChainAdapterException.MalformedResponse("getObject result missing 'data.content'")
        val fields =
            content["fields"]?.jsonObject
                ?: throw SuiChainAdapterException.MalformedResponse("getObject result missing 'data.content.fields'")

        val modelHash = decodeModelHash(fields["model_hash"])
        val modelUri =
            fields["model_uri"]?.jsonPrimitive?.content
                ?: throw SuiChainAdapterException.MalformedResponse("fields missing 'model_uri'")
        val schemaVersion = fields["schema_version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        // packageId für queryEvents aus content.type extrahieren ("0xPKG::module::Struct")
        content["type"]?.jsonPrimitive?.content?.let { contentType ->
            packageId = contentType.substringBefore("::")
            module = contentType.substringAfter("::").substringBefore("::")
        }

        return ContractIdentity(contractAddress, modelHash, modelUri, schemaVersion)
    }

    /**
     * Live-Strom aller eingehenden Sui-Events ab cursor=null (emittiert ALLE Events).
     * Cold Flow: jede Collection startet mit frischem cursor=null.
     * Unendlich — mit `.take(n)` oder Cancel begrenzen.
     */
    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val pkg = requirePackage()
            var cursor: JsonElement? = null
            while (true) {
                var hasNext = true
                while (hasNext) {
                    val res = client.queryEvents(pkg, module, cursor, pageLimit, descending = false).jsonObject
                    val data = res["data"] ?: JsonArray(emptyList())
                    for (e in eventDecoder.decodeAll(data)) emit(e)
                    hasNext = res["hasNextPage"]?.jsonPrimitive?.booleanOrNull ?: false
                    val next = res["nextCursor"]?.takeIf { it !is JsonNull }
                    if (next != null) cursor = next else hasNext = false
                }
                delay(pollIntervalMillis)
            }
        }

    /**
     * Historischer Replay aller Sui-Events ab [fromBlock] (Checkpoint-Sequenznummer).
     * Terminiert wenn keine weiteren Pages vorhanden.
     *
     * @param fromBlock Minimale Checkpoint-Sequenznummer (inklusive).
     */
    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val pkg = requirePackage()
            var cursor: JsonElement? = null
            var hasNext = true
            while (hasNext) {
                val res = client.queryEvents(pkg, module, cursor, pageLimit, descending = false).jsonObject
                val events = eventDecoder.decodeAll(res["data"] ?: JsonArray(emptyList()))
                for (e in events) {
                    if (e.blockNumber >= fromBlock) emit(e)
                }
                hasNext = res["hasNextPage"]?.jsonPrimitive?.booleanOrNull ?: false
                val next = res["nextCursor"]?.takeIf { it !is JsonNull }
                if (next != null) cursor = next else hasNext = false
            }
        }
    }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    /**
     * Dekodiert model_hash aus drei möglichen Formen:
     * - JsonArray von Int-Werten: Vec<u8> als Int-Array (häufigste Sui-Form)
     * - JsonPrimitive (Base64-String): z.B. "AQIDBA=="
     * - JsonPrimitive (Hex-String mit 0x-Präfix): z.B. "0x01020304"
     */
    private fun decodeModelHash(el: JsonElement?): ByteArray =
        when {
            el == null || el is JsonNull ->
                throw SuiChainAdapterException.MalformedResponse("fields missing 'model_hash'")
            el is JsonArray ->
                ByteArray(el.size) { i -> el[i].jsonPrimitive.int.toByte() }
            el is JsonPrimitive -> {
                val s = el.content
                if (s.startsWith("0x") || s.startsWith("0X")) hexToBytes(s) else Base64Decoder.decode(s)
            }
            else ->
                throw SuiChainAdapterException.MalformedResponse("Unexpected model_hash shape: $el")
        }

    private fun requireClient(): SuiRpcClient = rpcClient ?: error("SuiChainAdapter not connected — call connect() first")

    private fun requirePackage(): String = packageId ?: error("SuiChainAdapter not connected — call connect() first")

    public companion object {
        /** Standard-Modulname für kUML-Events auf Sui. */
        public const val DEFAULT_MODULE: String = "kuml"

        /**
         * Konvertiert einen 0x-Hex-String in ein ByteArray.
         * Wirft [SuiChainAdapterException.MalformedResponse] bei ungültigem Hex.
         */
        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return ByteArray(0)
            if (clean.length % 2 != 0) {
                throw SuiChainAdapterException.MalformedResponse("Hex string has odd length: '$hex'")
            }
            return try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: NumberFormatException) {
                throw SuiChainAdapterException.MalformedResponse("Invalid hex string: '$hex'", e)
            }
        }
    }
}
