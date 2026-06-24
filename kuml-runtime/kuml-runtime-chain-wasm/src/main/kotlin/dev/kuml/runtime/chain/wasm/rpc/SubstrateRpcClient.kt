package dev.kuml.runtime.chain.wasm.rpc

import dev.kuml.runtime.chain.ContractIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * V3.0.22 — Schlanker Substrate JSON-RPC-2.0-Client ueber java.net.http.HttpClient.
 *
 * Adaptiert aus dem Cosmos-Substrate-Client fuer das eigenstaendige WASM-Modul.
 * Substrate nutzt JSON-RPC 2.0 ueber HTTP-POST (gleicher Envelope wie EVM/Cosmos).
 *
 * Relevant fuer den WASM-Adapter:
 * - `chain_getFinalizedHead` + `chain_getHeader` → aktuelle Block-Hoehe ([currentHead])
 * - `chain_getBlockHash(height)` + `state_getStorage(System.Events)` → Events pro Block
 * - `state_getStorage(Timestamp.Now)` → Block-Zeitstempel ([currentBlockTimestamp])
 * - `state_call(ContractsApi_call)` → read-only ink!-Message fuer Registry-Abruf
 *
 * RawContractEvent kapselt ein einzelnes ContractEmitted-Event aus dem System.Events-Blob.
 *
 * @property rpcUrl Endpunkt (https/wss → https; http/ws → http via [toHttpUrl]).
 * @property maxResponseBytes Maximale RPC-Antwortgroesse in Bytes (Default 10 MB, DoS-Schutz).
 */
public open class SubstrateRpcClient(
    private val rpcUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val idCounter = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ein einzelnes dekodiertes ContractEmitted-Event aus dem System.Events-Storage-Blob.
     *
     * @property blockNumber Block-Hoehe in der das Event emittiert wurde.
     * @property extrinsicHash Hash der Extrinsic (Transaktion), die das Event ausgeloest hat.
     * @property topicsHex Topics als 0x-Hex-Strings (erstes Topic identifiziert den Event-Typ).
     * @property dataScale SCALE-kodierter data-Blob (nur die nicht-indizierten Felder).
     */
    public data class RawContractEvent(
        val blockNumber: Long,
        val extrinsicHash: String,
        val topicsHex: List<String>,
        val dataScale: ByteArray,
    ) {
        // ByteArray braucht manuelles equals/hashCode fuer korrekte Semantik in data class.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawContractEvent) return false
            return blockNumber == other.blockNumber &&
                extrinsicHash == other.extrinsicHash &&
                topicsHex == other.topicsHex &&
                dataScale.contentEquals(other.dataScale)
        }

        override fun hashCode(): Int {
            var r = blockNumber.hashCode()
            r = 31 * r + extrinsicHash.hashCode()
            r = 31 * r + topicsHex.hashCode()
            r = 31 * r + dataScale.contentHashCode()
            return r
        }
    }

    // -------------------------------------------------------------------------
    // Public API (genutzt von SubstrateWasmAdapter)
    // -------------------------------------------------------------------------

    /**
     * Liefert die aktuelle finalisierte Block-Hoehe.
     * Ruft `chain_getFinalizedHead` + `chain_getHeader` auf.
     */
    public open suspend fun currentHead(): Long {
        val headHash = call("chain_getFinalizedHead", buildJsonArray {}).jsonPrimitive.content
        val header = call("chain_getHeader", buildJsonArray { add(JsonPrimitive(headHash)) }).jsonObject
        val numberHex =
            header["number"]?.jsonPrimitive?.content
                ?: throw SubstrateWasmException.MalformedResponse("chain_getHeader missing 'number'")
        return parseHexQuantity(numberHex)
    }

    /**
     * Liefert alle ContractEmitted-Events des angegebenen Blocks fuer die Ziel-Adresse.
     *
     * Ablauf:
     * 1. `chain_getBlockHash(block)` → Block-Hash
     * 2. `state_getStorage(System.Events, blockHash)` → SCALE-Hex
     * 3. SCALE-Hex → RawContractEvent-Liste (gefiltert nach [contractAddress])
     *
     * Hinweis: Der vollstaendige SCALE-Decoder fuer System.Events ist komplex (jede Event-Variante
     * hat ein eigenes Format). Hier wird ein vereinfachter Best-Effort-Parser verwendet, der
     * Contracts.ContractEmitted-Events anhand des Pallet-Index extrahiert. Fuer eine produktive
     * Deployment sollte der ABI-basierte Decoder aus [SubstrateSystemEventsParser] verwendet werden.
     */
    public open suspend fun contractEmittedAt(
        block: Long,
        contractAddress: String,
    ): List<RawContractEvent> {
        val blockHash = getBlockHash(block)
        val eventsHex = getSystemEventsHex(blockHash)
        if (eventsHex.isEmpty() || eventsHex == "0x") return emptyList()
        return SubstrateSystemEventsParser.parseContractEmitted(eventsHex, block, contractAddress)
    }

    /**
     * Liest die KumlRegistry-ContractIdentity des ink!-Contracts via read-only Message-Call.
     *
     * Ruft die ink!-Message `get_kuml_identity` (Selector 0x00000001 als Platzhalter — echter
     * Selector kommt aus der ABI) auf und dekodiert die Antwort als ContractIdentity.
     */
    public open suspend fun readRegistryIdentity(contractAddress: String): ContractIdentity {
        // Vereinfacht: liest direkt aus dem Storage-Slot fuer die Registry-Identity.
        // In der vollen Implementierung wuerde hier der ABI-Selector fuer get_kuml_identity
        // genutzt. Fuer Tests injiziert der Adapter ein abiProvider-Lambda.
        val result = contractsCall(contractAddress, GET_KUML_IDENTITY_SELECTOR)
        return parseContractIdentity(contractAddress, result)
    }

    /**
     * Laedt das ink!-Metadata-JSON fuer einen Contract.
     * Ruft `get_metadata` Message auf und gibt das JSON-Dokument als String zurueck.
     */
    public open suspend fun fetchContractMetadata(contractAddress: String): String {
        val result = contractsCall(contractAddress, GET_METADATA_SELECTOR)
        return parseMetadataString(result)
    }

    /**
     * Liefert den Zeitstempel des aktuellen Blocks (via pallet-timestamp Storage).
     */
    public fun currentBlockTimestamp(): Instant {
        // Synchroner Zugriff; in Tests wird dieser Wert durch Mock-Injection gesteuert.
        // In der echten Implementierung wuerde hier getTimestampNow() suspend aufgerufen.
        // Fuer die BlockClock-Schnittstelle (non-suspend) geben wir wall-clock als Approximation.
        return Instant.now()
    }

    // -------------------------------------------------------------------------
    // Internal RPC helpers
    // -------------------------------------------------------------------------

    /** Generischer JSON-RPC-2.0-Call. */
    public suspend fun call(
        method: String,
        params: JsonElement,
    ): JsonElement {
        val requestId = idCounter.getAndIncrement()
        val body =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("method", method)
                put("params", params)
            }.toString()

        val httpRequest =
            HttpRequest
                .newBuilder()
                .uri(URI.create(toHttpUrl(rpcUrl)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(requestTimeout)
                .build()

        val responseBody =
            withContext(Dispatchers.IO) {
                try {
                    val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299) {
                        throw SubstrateWasmException.NetworkError(
                            "HTTP ${response.statusCode()} from ${sanitizeUrl(rpcUrl)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: SubstrateWasmException) {
                    throw e
                } catch (e: Exception) {
                    throw SubstrateWasmException.NetworkError(
                        "IO error calling ${sanitizeUrl(rpcUrl)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw SubstrateWasmException.MalformedResponse("Could not parse JSON-RPC response: ${e.message}", e)
            }

        val obj =
            parsed as? JsonObject
                ?: throw SubstrateWasmException.MalformedResponse("JSON-RPC response is not an object")

        val errorObj = obj["error"]
        if (errorObj != null && errorObj !is JsonNull) {
            val errObj =
                errorObj as? JsonObject
                    ?: throw SubstrateWasmException.MalformedResponse("JSON-RPC error field is not an object")
            val code =
                errObj["code"]?.jsonPrimitive?.int
                    ?: throw SubstrateWasmException.MalformedResponse("JSON-RPC error missing code")
            val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown"
            val data = errObj["data"]?.jsonPrimitive?.content
            throw SubstrateWasmException.RpcError(code, message, data)
        }

        return obj["result"]
            ?: throw SubstrateWasmException.MalformedResponse("JSON-RPC response missing 'result' field")
    }

    private suspend fun getBlockHash(height: Long): String {
        val params = buildJsonArray { add(JsonPrimitive(height)) }
        val res = call("chain_getBlockHash", params)
        if (res is JsonNull) {
            throw SubstrateWasmException.MalformedResponse("no block hash for height $height")
        }
        return res.jsonPrimitive.content
    }

    private suspend fun getSystemEventsHex(blockHash: String): String {
        val params =
            buildJsonArray {
                add(JsonPrimitive(SYSTEM_EVENTS_KEY))
                add(JsonPrimitive(blockHash))
            }
        val res = call("state_getStorage", params)
        return if (res is JsonNull) "" else res.jsonPrimitive.content
    }

    /** Read-only ink!-Message-Call via state_call(ContractsApi_call). */
    public suspend fun contractsCall(
        contractAddress: String,
        selectorHex: String,
    ): String {
        val selectorBytes = hexToBytes(selectorHex)
        val destAccountId = decodeAccountId(contractAddress)
        val args = buildScaleContractsApiCallArgs(destAccountId, selectorBytes)
        val argsHex = "0x" + args.joinToString("") { "%02x".format(it) }

        val params =
            buildJsonArray {
                add(JsonPrimitive("ContractsApi_call"))
                add(JsonPrimitive(argsHex))
            }
        val res = call("state_call", params)
        if (res is JsonPrimitive) return res.content
        val obj =
            res as? JsonObject
                ?: throw SubstrateWasmException.MalformedResponse("ContractsApi_call result is neither hex nor object")
        return obj["result"]
            ?.let { it as? JsonObject }
            ?.get("Ok")
            ?.let { it as? JsonObject }
            ?.get("data")
            ?.jsonPrimitive
            ?.content
            ?: obj["data"]?.jsonPrimitive?.content
            ?: throw SubstrateWasmException.MalformedResponse("ContractsApi_call result missing data")
    }

    /**
     * Baut die SCALE-codierten Argumente fuer ContractsApi_call zusammen (Read-only-Variante).
     */
    private fun buildScaleContractsApiCallArgs(
        destAccountId: ByteArray,
        inputData: ByteArray,
    ): ByteArray {
        val origin = ByteArray(32) { 0 }
        val value = ByteArray(16) { 0 }
        val gasLimitRefTime = READ_ONLY_GAS_LIMIT_REF_TIME.toLeBytes()
        val gasLimitProofSize = READ_ONLY_GAS_LIMIT_PROOF_SIZE.toLeBytes()
        val storageDepositLimit = byteArrayOf(0x00)
        val inputDataCompact = scaleCompact(inputData.size) + inputData
        return origin + destAccountId + value + gasLimitRefTime + gasLimitProofSize + storageDepositLimit + inputDataCompact
    }

    private fun Long.toLeBytes(): ByteArray {
        val b = ByteArray(8)
        var v = this
        for (i in 0 until 8) {
            b[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return b
    }

    /**
     * SCALE Compact-Encoding fuer nicht-negative Int-Werte.
     * Delegiert an [ScaleCodec.encodeCompact] um doppelte Implementierung und Divergenz zu vermeiden.
     */
    private fun scaleCompact(value: Int): ByteArray =
        dev.kuml.runtime.chain.wasm.scale.ScaleCodec
            .encodeCompact(value.toLong())

    /**
     * Dekodiert eine SS58-Adresse zu einem 32-Byte AccountId.
     * Unterstuetzt einzel-Byte (Netzwerk-ID 0-63) und zwei-Byte (64-16383) Prefix.
     */
    private fun decodeAccountId(address: String): ByteArray {
        val decoded =
            base58Decode(address)
                ?: throw SubstrateWasmException.InvalidAddress(address)
        val prefixLen = if (decoded.size == 35) 1 else 2
        if (decoded.size < prefixLen + 32) throw SubstrateWasmException.InvalidAddress(address)
        return decoded.copyOfRange(prefixLen, prefixLen + 32)
    }

    /**
     * Parst das SCALE-Ergebnis von readRegistryIdentity als ContractIdentity.
     * Erwartet ein SCALE-kodiertes Tupel: (modelHash: [u8;32], modelUri: Vec<u8>, schemaVersion: u32).
     */
    private fun parseContractIdentity(
        address: String,
        scaleHex: String,
    ): ContractIdentity {
        val bytes = hexToBytes(scaleHex)
        if (bytes.size < 32) {
            throw SubstrateWasmException.MalformedResponse(
                "ContractIdentity SCALE response too short: ${bytes.size} bytes",
            )
        }
        // Simplified: read first 32 bytes as modelHash, then Vec<u8> as modelUri, then u32 as schemaVersion.
        val modelHash = bytes.copyOfRange(0, 32)
        var pos = 32
        // Vec<u8>: compact length + bytes
        if (pos >= bytes.size) {
            return ContractIdentity(address, modelHash, "", 1)
        }
        val uriLen = scaleCompactDecode(bytes, pos)
        pos += uriLen.second
        val uriEnd = pos + uriLen.first
        if (uriEnd > bytes.size) {
            throw SubstrateWasmException.MalformedResponse(
                "ContractIdentity SCALE: Vec<u8> URI claims ${ uriLen.first } bytes but only ${ bytes.size - pos } remain",
            )
        }
        val modelUri = String(bytes.copyOfRange(pos, uriEnd), Charsets.UTF_8)
        pos = uriEnd
        val schemaVersion =
            if (pos + 4 <= bytes.size) {
                (
                    (bytes[pos].toInt() and 0xFF) or
                        ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                )
            } else {
                1
            }
        return ContractIdentity(address, modelHash, modelUri, schemaVersion)
    }

    /** Parst das SCALE-Ergebnis von fetchContractMetadata als JSON-String. */
    private fun parseMetadataString(scaleHex: String): String {
        val bytes = hexToBytes(scaleHex)
        if (bytes.isEmpty()) return "{}"
        // Erwarte Vec<u8> (Compact-Laenge + UTF-8-Bytes)
        val lenResult = scaleCompactDecode(bytes, 0)
        val start = lenResult.second
        val end = start + lenResult.first
        if (end > bytes.size) {
            throw SubstrateWasmException.MalformedResponse(
                "Metadata SCALE: Vec<u8> claims ${ lenResult.first } bytes but only ${ bytes.size - start } remain",
            )
        }
        return String(bytes.copyOfRange(start, end), Charsets.UTF_8)
    }

    /**
     * Dekodiert SCALE Compact-Integer an Position [pos] und gibt (Wert, AnzahlBytes) zurueck.
     * Wirft [SubstrateWasmException.MalformedResponse] bei Truncation (zu wenige Bytes),
     * anstatt still (0, 0) zurueckzugeben — sonst wuerde ein abgeschnittener Vec<u8>-Prefix
     * als Laenge 0 interpretiert und eine leere modelUri zurueckgegeben statt eines Fehlers.
     */
    private fun scaleCompactDecode(
        data: ByteArray,
        pos: Int,
    ): Pair<Int, Int> {
        if (pos >= data.size) {
            throw SubstrateWasmException.MalformedResponse(
                "SCALE Compact decode: no bytes available at pos $pos (data size ${data.size})",
            )
        }
        val first = data[pos].toInt() and 0xFF
        return when (first and 0b11) {
            0b00 -> Pair(first ushr 2, 1)
            0b01 -> {
                if (pos + 1 >= data.size) {
                    throw SubstrateWasmException.MalformedResponse(
                        "SCALE Compact two-byte mode: truncated at pos $pos (data size ${data.size})",
                    )
                }
                val v = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)) ushr 2
                Pair(v, 2)
            }
            0b10 -> {
                if (pos + 3 >= data.size) {
                    throw SubstrateWasmException.MalformedResponse(
                        "SCALE Compact four-byte mode: truncated at pos $pos (data size ${data.size})",
                    )
                }
                var v = 0
                for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
                Pair(v ushr 2, 4)
            }
            else -> throw SubstrateWasmException.MalformedResponse(
                "SCALE Compact big-integer mode not supported in RPC client compact decoder at pos $pos",
            )
        }
    }

    public companion object {
        /** Maximale erlaubte RPC-Response-Groesse (10 MB). Schuetzt vor Heap-Exhaustion. */
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10_485_760L

        /** Gas-Limit fuer Read-only-Calls (50 Mrd ref_time, 5 MB proof_size). */
        public const val READ_ONLY_GAS_LIMIT_REF_TIME: Long = 50_000_000_000L
        public const val READ_ONLY_GAS_LIMIT_PROOF_SIZE: Long = 5_242_880L

        /** Storage-Key fuer System.Events (twox128("System")+twox128("Events")). */
        public const val SYSTEM_EVENTS_KEY: String =
            "0x26aa394eea5630e07c48ae0c9558cef780d41e5e16056765bc8461851072c9d7"

        /** Storage-Key fuer Timestamp.Now (twox128("Timestamp")+twox128("Now")). */
        public const val TIMESTAMP_NOW_KEY: String =
            "0xf0c365c3cf59d671eb72da0e7a4113c49f1f0515f462cdcf84e0f1d6045dfcbb"

        /** Platzhalter-Selector fuer get_kuml_identity (echter Wert kommt aus ABI). */
        public const val GET_KUML_IDENTITY_SELECTOR: String = "0x00000001"

        /** Platzhalter-Selector fuer get_metadata (echter Wert kommt aus ABI). */
        public const val GET_METADATA_SELECTOR: String = "0x00000002"

        /** wss/ws → https/http fuer HTTP-POST-Transport. */
        public fun toHttpUrl(url: String): String =
            when {
                url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
                url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
                else -> url
            }

        /** Parst 0x-Hex-Quantity → Long. */
        public fun parseHexQuantity(hex: String): Long {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return 0L
            return clean.toLongOrNull(16)
                ?: throw SubstrateWasmException.MalformedResponse("Not a valid hex quantity: '$hex'")
        }

        /** Entfernt Credentials aus URL vor Logging/Exceptions. */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(toHttpUrl(url))
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /** Liest maximal [limit] Bytes aus [stream]. Wirft NetworkError bei Ueberschreitung. */
        public fun readLimited(
            stream: InputStream,
            limit: Long,
        ): String {
            val buffer = ByteArray(8192)
            val sb = StringBuilder()
            var totalRead = 0L
            stream.use { s ->
                var n: Int
                while (s.read(buffer).also { n = it } != -1) {
                    totalRead += n
                    if (totalRead > limit) {
                        throw SubstrateWasmException.NetworkError(
                            "RPC response exceeds maximum allowed size of $limit bytes",
                        )
                    }
                    sb.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            }
            return sb.toString()
        }

        /** Erzeugt einen Standard-HttpClient mit Default-Timeouts. */
        public fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        /** Hex-String → ByteArray. */
        public fun hexToBytes(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return ByteArray(0)
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Base58-Decoder fuer SS58-Adressen mit Blake2b-512-Checksum-Verifizierung.
         *
         * SS58-Checksum-Spec: die letzten 2 Bytes der dekodieren Byte-Folge muessen den ersten
         * 2 Bytes von Blake2b-512("SS58PRE" + prefix_bytes + accountId) entsprechen.
         * Da `java.security` kein Blake2b bietet, verwenden wir einen Pseudo-Checksum-Pfad:
         * die Laenge wird geprueft (34 oder 35 Bytes — Single-Byte-Prefix oder Doppel-Byte-Prefix)
         * und dann wird die Checksum-Verifizierung ueber den eingebauten [ss58Checksum] Helper
         * durchgefuehrt. Gibt null zurueck wenn die Eingabe kein gueltiges Base58 ist oder die
         * Checksum ungueltig ist.
         */
        public fun base58Decode(encoded: String): ByteArray? {
            val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            var result = java.math.BigInteger.ZERO
            val base = java.math.BigInteger.valueOf(58)
            for (c in encoded) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) return null
                result = result.multiply(base).add(java.math.BigInteger.valueOf(idx.toLong()))
            }
            val rawBytes = result.toByteArray()
            // Fuehrende Nullbytes (Base58 '1') addieren
            val leadingZeros = encoded.takeWhile { it == '1' }.length
            val bytes =
                ByteArray(leadingZeros) +
                    if (rawBytes[0] == 0.toByte() && rawBytes.size > 1) rawBytes.copyOfRange(1, rawBytes.size) else rawBytes

            // SS58: erwartete Gesamtlaenge ist prefixLen + 32 (AccountId) + 2 (Checksum)
            // prefixLen = 1 (Netzwerk-ID 0-63) oder 2 (Netzwerk-ID 64-16383)
            if (bytes.size != 35 && bytes.size != 36) return null
            val prefixLen = if (bytes.size == 35) 1 else 2
            val payload = bytes.copyOfRange(0, prefixLen + 32)
            val claimedChecksum = bytes.copyOfRange(prefixLen + 32, prefixLen + 34)
            val computedChecksum = ss58Checksum(payload)
            if (!claimedChecksum.contentEquals(computedChecksum)) return null
            return bytes
        }

        /**
         * Berechnet die SS58-Checksum: erste 2 Bytes von Blake2b-512("SS58PRE" + payload).
         *
         * Nutzt [Blake2b512] — eine reine Kotlin-Implementierung ohne externe Abhaengigkeiten,
         * damit das Modul native-image-tauglich und dep-minimal bleibt.
         */
        public fun ss58Checksum(payload: ByteArray): ByteArray {
            val prefix = "SS58PRE".toByteArray(Charsets.UTF_8)
            val input = prefix + payload
            val hash = Blake2b512.hash(input)
            return hash.copyOfRange(0, 2)
        }
    }
}

/**
 * Heuristischer Parser fuer Substrate System.Events Storage-Blob.
 *
 * Der vollstaendige System.Events-Decoder erfordert die Runtime-Metadata (SCALE-kodierte
 * Typregistrierung der Chain). Hier wird ein Pattern-Scan-Ansatz verwendet:
 * ContractEmitted-Events werden anhand des Pallet-Index-Bytes im SCALE-Blob erkannt,
 * gefolgt vom Event-Index-Byte (0x00 = ContractEmitted in pallet-contracts).
 *
 * Fuer Produktions-Deployments sollte der exakte Pallet-Index via Runtime-Metadata
 * ermittelt werden. Als Default-Pallet-Index wird 70 (0x46) angenommen — typisch
 * fuer pallet-contracts in Standard-Substrate-Ketten (z.B. Contracts-Node).
 *
 * Format eines einzelnen System.Events-Eintrags (vereinfacht, ohne Runtime-Metadata):
 * ```
 * [phase: u8 variant]      — 0x00=ApplyExtrinsic(u32), 0x01=Finalization, 0x02=Initialization
 * [pallet_index: u8]       — Index des Pallets in der Runtime
 * [event_index: u8]        — Index des Events im Pallet
 * [event_data: ...]        — variabler Payload (formatspezifisch)
 * [topics: Vec<H256>]      — Topics (0x00 = leer bei alten Chains)
 * ```
 *
 * ContractEmitted im pallet-contracts hat folgendes Event-Daten-Format:
 * ```
 * contract: AccountId32 (32 bytes)
 * topics:   Vec<H256> (Compact-Laenge + N*32 Bytes)
 * data:     Vec<u8>   (Compact-Laenge + N Bytes)
 * ```
 */
public object SubstrateSystemEventsParser {
    /** Default-Pallet-Index fuer pallet-contracts in Standard-Substrate-Ketten. */
    public const val DEFAULT_CONTRACTS_PALLET_INDEX: Int = 70

    /** Event-Index fuer ContractEmitted in pallet-contracts. */
    public const val CONTRACT_EMITTED_EVENT_INDEX: Int = 0

    /** Maximale Anzahl Topics pro Event (DoS-Schutz). */
    private const val MAX_TOPICS = 16

    /** Maximale Datenmenge pro Event-Payload (DoS-Schutz: 512 KB). */
    private const val MAX_DATA_BYTES = 524_288

    /**
     * Extrahiert ContractEmitted-Events aus dem System.Events SCALE-Hex-String.
     *
     * Verwendet einen Pattern-Scan ueber den SCALE-Blob: findet alle Positionen an denen
     * [contractsPalletIndex] + [CONTRACT_EMITTED_EVENT_INDEX] aufeinander folgen, und versucht
     * dort einen ContractEmitted-Event zu dekodieren. Invalide Positionen werden uebersprungen.
     *
     * @param eventsHex     SCALE-kodierter System.Events-Blob als 0x-Hex-String.
     * @param blockNumber   Block-Hoehe (fuer RawContractEvent).
     * @param contractAddress SS58-Adresse des Ziel-Contracts (als Filter).
     * @param contractsPalletIndex Pallet-Index von pallet-contracts (Default: 70).
     */
    public fun parseContractEmitted(
        eventsHex: String,
        blockNumber: Long,
        contractAddress: String,
        contractsPalletIndex: Int = DEFAULT_CONTRACTS_PALLET_INDEX,
    ): List<SubstrateRpcClient.RawContractEvent> {
        val blob = SubstrateRpcClient.hexToBytes(eventsHex)
        if (blob.size < 4) return emptyList()

        // Dekodiere die Zieladresse zu 32 Bytes fuer spaeter Filterung
        val targetAccountId: ByteArray? =
            runCatching {
                val decoded =
                    SubstrateRpcClient.base58Decode(contractAddress)
                        ?: return emptyList()
                val prefixLen = if (decoded.size == 35) 1 else 2
                decoded.copyOfRange(prefixLen, prefixLen + 32)
            }.getOrNull()

        val result = mutableListOf<SubstrateRpcClient.RawContractEvent>()

        // Pattern-Scan: suche Byte-Paare [contractsPalletIndex, CONTRACT_EMITTED_EVENT_INDEX]
        val palletByte = (contractsPalletIndex and 0xFF).toByte()
        val eventByte = (CONTRACT_EMITTED_EVENT_INDEX and 0xFF).toByte()

        var i = 1 // Start nach Compact-Laenge-Prefix des Events-Vektors
        while (i < blob.size - 1) {
            if (blob[i] == palletByte && blob[i + 1] == eventByte) {
                // Versuch: Dekodiere ContractEmitted ab Position i+2
                val pos = i + 2
                val event = tryDecodeContractEmitted(blob, pos, blockNumber, targetAccountId)
                if (event != null) {
                    result.add(event)
                }
            }
            i++
        }
        return result
    }

    /**
     * Versucht einen ContractEmitted-Event ab [startPos] zu dekodieren.
     * Gibt null zurueck bei Parsefehler (Pattern-Match war falsch-positiv).
     */
    private fun tryDecodeContractEmitted(
        blob: ByteArray,
        startPos: Int,
        blockNumber: Long,
        targetAccountId: ByteArray?,
    ): SubstrateRpcClient.RawContractEvent? =
        runCatching {
            var pos = startPos
            if (pos + 32 > blob.size) return null

            // contract: AccountId32 (32 bytes)
            val contractId = blob.copyOfRange(pos, pos + 32)
            pos += 32

            // Filter: nur Events fuer den Ziel-Contract
            if (targetAccountId != null && !contractId.contentEquals(targetAccountId)) return null

            // topics: Vec<H256> (Compact-Laenge + N * 32 Bytes)
            if (pos >= blob.size) return null
            val topicsLen = decodeCompactInt(blob, pos) ?: return null
            if (topicsLen.first > MAX_TOPICS) return null
            pos += topicsLen.second
            val topicsHex = mutableListOf<String>()
            repeat(topicsLen.first) {
                if (pos + 32 > blob.size) return null
                val topic = blob.copyOfRange(pos, pos + 32)
                topicsHex.add("0x" + topic.joinToString("") { "%02x".format(it) })
                pos += 32
            }

            // data: Vec<u8> (Compact-Laenge + N Bytes)
            if (pos >= blob.size) return null
            val dataLen = decodeCompactInt(blob, pos) ?: return null
            if (dataLen.first > MAX_DATA_BYTES) return null
            pos += dataLen.second
            if (pos + dataLen.first > blob.size) return null
            val dataBytes = blob.copyOfRange(pos, pos + dataLen.first)

            // extrinsicHash: Der echte Extrinsic-Hash ist im Pattern-Scan-Ansatz nicht
            // zuverlaessig rekonstruierbar (der Phase-Block enthaelt nur den Extrinsic-Index,
            // nicht den Hash). Leer lassen — Downstream-Consumer die txHash nutzen, sollen
            // via chain_getBlock den Hash aus dem Extrinsic-Index ableiten.
            SubstrateRpcClient.RawContractEvent(
                blockNumber = blockNumber,
                extrinsicHash = "",
                topicsHex = topicsHex,
                dataScale = dataBytes,
            )
        }.getOrNull()

    /** Dekodiert einen SCALE Compact-Integer an [pos]. Gibt (wert, bytesVerbraucht) zurueck, oder null. */
    private fun decodeCompactInt(
        data: ByteArray,
        pos: Int,
    ): Pair<Int, Int>? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        return when (first and 0b11) {
            0b00 -> Pair(first ushr 2, 1)
            0b01 -> {
                if (pos + 1 >= data.size) return null
                val v = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)) ushr 2
                Pair(v, 2)
            }
            0b10 -> {
                if (pos + 3 >= data.size) return null
                var v = 0
                for (idx in 0 until 4) v = v or ((data[pos + idx].toInt() and 0xFF) shl (8 * idx))
                Pair(v ushr 2, 4)
            }
            else -> null // big-integer mode nicht unterstuetzt fuer Laengen
        }
    }
}
