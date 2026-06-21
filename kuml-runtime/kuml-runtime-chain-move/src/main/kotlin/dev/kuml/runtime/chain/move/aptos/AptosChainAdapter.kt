package dev.kuml.runtime.chain.move.aptos

import dev.kuml.runtime.chain.BlockClock
import dev.kuml.runtime.chain.ChainEvent
import dev.kuml.runtime.chain.ContractIdentity
import dev.kuml.runtime.chain.KumlChainAdapter
import dev.kuml.runtime.chain.move.Base64Decoder
import dev.kuml.runtime.chain.move.MoveAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V3.0.20 — Aptos-Implementierung von [KumlChainAdapter].
 *
 * Bindet ein on-chain registriertes kUML-Modell an ein Aptos-Account-Resource:
 * - [connect] liest modelHash/modelUri/schemaVersion via GET /v1/accounts/{addr}/resource/{type}.
 * - [subscribe] pollt `getEvents` ab seq=0 (Cold Flow, unendlich).
 * - [replay] liefert historische Events ab fromBlock (Ledger-Version) bis alle Seiten gelesen.
 *
 * Cold-Flow-Garantie: seq-State ist lokale `flow{}`-Variable, NICHT Instanz-Feld.
 * Thread-Safety: [connect] muss vollständig abgeschlossen sein bevor [subscribe]/[replay].
 *
 * Die ContractIdentity.address enthält den zusammengesetzten Identifier:
 * "{accountAddress}::{eventHandleStruct}/{eventFieldName}"
 *
 * @param urlValidator SSRF-Validierung. Für Tests: [AptosUrlValidator.NoOp] injizieren.
 * @param pollIntervalMillis Poll-Abstand für [subscribe] (Default 2 000 ms).
 * @param pageLimit Events pro getEvents-Call.
 * @param resourceTypeTag Resource-Type-Tag des kUML-Registry-Move-Moduls.
 * @param eventHandleStruct Struct-Type-Tag des Event-Handles.
 * @param eventFieldName Feldname des Event-Handle-Feldes in der Ressource.
 * @param clientFactory Factory für [AptosRestClient] (injizierbar für Tests).
 * @param eventDecoder Decoder für Event-Objekte (injizierbar für Tests).
 */
public class AptosChainAdapter(
    private val urlValidator: AptosUrlValidator = AptosUrlValidator.Default,
    private val pollIntervalMillis: Long = 2_000L,
    private val pageLimit: Int = 100,
    private val resourceTypeTag: String = DEFAULT_RESOURCE_TYPE_TAG,
    private val eventHandleStruct: String = DEFAULT_EVENT_HANDLE_STRUCT,
    private val eventFieldName: String = DEFAULT_EVENT_FIELD,
    private val clientFactory: (String) -> AptosRestClient = { url -> AptosRestClient(url) },
    private val eventDecoder: AptosEventDecoder = AptosEventDecoder(),
) : KumlChainAdapter {
    private var restClient: AptosRestClient? = null
    private var blockClock: AptosBlockClock? = null
    private var accountAddress: String? = null

    /**
     * Stellt Verbindung zu einem Aptos-Account mit kUML-Registry-Ressource her.
     *
     * @param rpcUrl Aptos-Fullnode-Basis-URL (z.B. "https://fullnode.mainnet.aptoslabs.com").
     * @param contractAddress Account-Adresse (0x + 1..64 hex).
     * @return [ContractIdentity] mit zusammengesetzter Adresse und Modell-Metadaten.
     * @throws AptosChainAdapterException.InvalidAddressException wenn contractAddress kein gültiges Format.
     * @throws AptosChainAdapterException.InvalidUrlException bei SSRF-Verletzung.
     */
    override suspend fun connect(
        rpcUrl: String,
        contractAddress: String,
    ): ContractIdentity {
        require(rpcUrl.isNotBlank()) { "rpcUrl must not be blank" }
        urlValidator.validate(rpcUrl)
        if (!MoveAddress.isValid(contractAddress)) {
            throw AptosChainAdapterException.InvalidAddressException(contractAddress)
        }
        val client = clientFactory(rpcUrl)
        restClient = client
        blockClock = AptosBlockClock(client)
        accountAddress = contractAddress

        val resource = client.getResource(contractAddress, resourceTypeTag)
        val data =
            resource["data"]?.jsonObject
                ?: throw AptosChainAdapterException.MalformedResponse("resource missing 'data'")
        val modelHashStr =
            data["model_hash"]?.jsonPrimitive?.content
                ?: throw AptosChainAdapterException.MalformedResponse("data missing 'model_hash'")
        val modelHash = decodeHash(modelHashStr)
        val modelUri =
            data["model_uri"]?.jsonPrimitive?.content
                ?: throw AptosChainAdapterException.MalformedResponse("data missing 'model_uri'")
        val schemaVersion = data["schema_version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        // ContractIdentity.address = "{account}::{handle}/{field}" (Spec-Konvention)
        val compositeAddress = "$contractAddress::$eventHandleStruct/$eventFieldName"
        return ContractIdentity(compositeAddress, modelHash, modelUri, schemaVersion)
    }

    /**
     * Live-Strom aller eingehenden Aptos-Events ab seq=0.
     * Cold Flow: jede Collection startet mit frischer seq=0.
     * Unendlich — mit `.take(n)` oder Cancel begrenzen.
     */
    override fun subscribe(): Flow<ChainEvent> =
        flow {
            val client = requireClient()
            val addr = requireAddress()
            var nextSeq = 0L
            while (true) {
                val events = client.getEvents(addr, eventHandleStruct, eventFieldName, nextSeq, pageLimit)
                val decoded = eventDecoder.decodeAll(events)
                for (e in decoded) emit(e)
                nextSeq += events.size
                delay(pollIntervalMillis)
            }
        }

    /**
     * Historischer Replay aller Aptos-Events ab [fromBlock] (Ledger-Version).
     * Terminiert wenn eine leere Seite zurückkommt oder die Seite kleiner als pageLimit ist.
     *
     * @param fromBlock Minimale Ledger-Version (inklusive).
     */
    override fun replay(fromBlock: Long): Flow<ChainEvent> {
        require(fromBlock >= 0) { "fromBlock must be >= 0, was $fromBlock" }
        return flow {
            val client = requireClient()
            val addr = requireAddress()
            var seq = 0L
            while (true) {
                val events = client.getEvents(addr, eventHandleStruct, eventFieldName, seq, pageLimit)
                if (events.isEmpty()) break
                for (e in eventDecoder.decodeAll(events)) {
                    if (e.blockNumber >= fromBlock) emit(e)
                }
                seq += events.size
                if (events.size < pageLimit) break // letzte Seite
            }
        }
    }

    override fun blockClock(): BlockClock = blockClock ?: error("blockClock() called before connect()")

    /**
     * Dekodiert model_hash aus Hex- oder Base64-String.
     * Aptos liefert typischerweise Hex-Strings mit 0x-Präfix.
     */
    private fun decodeHash(s: String): ByteArray = if (s.startsWith("0x") || s.startsWith("0X")) hexToBytes(s) else Base64Decoder.decode(s)

    private fun requireClient(): AptosRestClient = restClient ?: error("AptosChainAdapter not connected — call connect() first")

    private fun requireAddress(): String = accountAddress ?: error("AptosChainAdapter not connected — call connect() first")

    public companion object {
        /** Standard-Resource-Type-Tag für das kUML-Registry-Move-Modul auf Aptos. */
        public const val DEFAULT_RESOURCE_TYPE_TAG: String = "0x1::kuml::Registry"

        /** Standard-Event-Handle-Struct für kUML-Events auf Aptos. */
        public const val DEFAULT_EVENT_HANDLE_STRUCT: String = "0x1::kuml::Registry"

        /** Standard-Feldname des Event-Handle-Feldes. */
        public const val DEFAULT_EVENT_FIELD: String = "model_events"

        /**
         * Konvertiert einen 0x-Hex-String in ein ByteArray.
         * Wirft [AptosChainAdapterException.MalformedResponse] bei ungültigem Hex.
         */
        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return ByteArray(0)
            if (clean.length % 2 != 0) {
                throw AptosChainAdapterException.MalformedResponse("Hex string has odd length: '$hex'")
            }
            return try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: NumberFormatException) {
                throw AptosChainAdapterException.MalformedResponse("Invalid hex string: '$hex'", e)
            }
        }
    }
}
