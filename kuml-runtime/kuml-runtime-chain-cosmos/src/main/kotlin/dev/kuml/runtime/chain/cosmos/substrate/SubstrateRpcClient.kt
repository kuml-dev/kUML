package dev.kuml.runtime.chain.cosmos.substrate

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
import java.util.concurrent.atomic.AtomicLong

/**
 * V3.0.21 — Schlanker Substrate-JSON-RPC-2.0-Client über java.net.http.HttpClient.
 *
 * Substrate nutzt JSON-RPC 2.0 über POST (gleicher Envelope wie EVM/Cosmos). Methoden:
 * - `chain_getFinalizedHead` / `chain_getHeader` für die finalisierte Höhe.
 * - `chain_getBlockHash(height)` → Block-Hash (hex).
 * - `state_getStorage(System.Events-Key, blockHash)` → SCALE-encoded Events (hex).
 * - `state_getStorage(Timestamp.Now-Key)` → u64-LE Millis (hex).
 * - `contracts_call` (bzw. `state_call ContractsApi_call`) für read-only ink!-Messages.
 *
 * HINWEIS: Storage-Keys (xxhash/blake2-konkateniert) werden hier als Konstanten geführt;
 * eine vollständige Runtime-Metadata-Auflösung ist außerhalb des Scopes dieses Adapters.
 *
 * @property rpcUrl Endpunkt (https/wss → für HTTP-POST wird https genutzt).
 * @property maxResponseBytes Maximale Größe einer RPC-Antwort in Bytes (Default 10 MB).
 */
public class SubstrateRpcClient(
    private val rpcUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val idCounter = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }

    /** Generischer JSON-RPC-Call. Siehe CosmWasmRpcClient.call für Fehlersemantik. */
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
                        throw SubstrateChainAdapterException.NetworkError(
                            "HTTP ${response.statusCode()} from ${sanitizeUrl(rpcUrl)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: SubstrateChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw SubstrateChainAdapterException.NetworkError(
                        "IO error calling ${sanitizeUrl(rpcUrl)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw SubstrateChainAdapterException.MalformedResponse("Could not parse JSON-RPC response: ${e.message}", e)
            }

        val obj =
            parsed as? JsonObject
                ?: throw SubstrateChainAdapterException.MalformedResponse("JSON-RPC response is not an object")

        val errorObj = obj["error"]
        if (errorObj != null && errorObj !is JsonNull) {
            val errObj =
                errorObj as? JsonObject
                    ?: throw SubstrateChainAdapterException.MalformedResponse("JSON-RPC error field is not an object")
            val code =
                errObj["code"]?.jsonPrimitive?.int
                    ?: throw SubstrateChainAdapterException.MalformedResponse("JSON-RPC error missing code")
            val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown"
            val data = errObj["data"]?.jsonPrimitive?.content
            throw SubstrateChainAdapterException.RpcError(code, message, data)
        }

        return obj["result"]
            ?: throw SubstrateChainAdapterException.MalformedResponse("JSON-RPC response missing 'result' field")
    }

    /** `chain_getHeader(getFinalizedHead)` → number (hex) → Long. */
    public suspend fun getFinalizedHeight(): Long {
        val headHash = call("chain_getFinalizedHead", buildJsonArray {}).jsonPrimitive.content
        val header =
            call("chain_getHeader", buildJsonArray { add(JsonPrimitive(headHash)) }).jsonObject
        val numberHex =
            header["number"]?.jsonPrimitive?.content
                ?: throw SubstrateChainAdapterException.MalformedResponse("header missing 'number'")
        return parseHexQuantity(numberHex)
    }

    /** `chain_getBlockHash(height)` → Block-Hash (hex). */
    public suspend fun getBlockHash(height: Long): String {
        val params = buildJsonArray { add(JsonPrimitive(height)) }
        val res = call("chain_getBlockHash", params)
        if (res is JsonNull) {
            throw SubstrateChainAdapterException.MalformedResponse("no block hash for height $height")
        }
        return res.jsonPrimitive.content
    }

    /** `state_getStorage(System.Events, blockHash)` → SCALE-encoded Events (hex), oder "" wenn leer. */
    public suspend fun getSystemEvents(blockHash: String): String {
        val params =
            buildJsonArray {
                add(JsonPrimitive(SYSTEM_EVENTS_KEY))
                add(JsonPrimitive(blockHash))
            }
        val res = call("state_getStorage", params)
        return if (res is JsonNull) "" else res.jsonPrimitive.content
    }

    /** `state_getStorage(Timestamp.Now)` → u64-LE Millis (hex), oder "" wenn leer. */
    public suspend fun getTimestampNowHex(): String {
        val params = buildJsonArray { add(JsonPrimitive(TIMESTAMP_NOW_KEY)) }
        val res = call("state_getStorage", params)
        return if (res is JsonNull) "" else res.jsonPrimitive.content
    }

    /**
     * Read-only ink!-Message-Call via `state_call("ContractsApi_call", <SCALE-encoded-args>)`.
     *
     * Das Standard-Substrate-JSON-RPC kennt keine `contracts_call`-Methode. Der korrekte
     * Weg ist `state_call` mit dem Methodennamen `ContractsApi_call` und SCALE-codierten
     * Argumenten als hex. Das Argument-Format für ContractsApi_call ist:
     *
     *   origin (AccountId32 = 32 Bytes, Zufallswert für Read-only ok)
     *   dest   (AccountId32 = 32 Bytes aus SS58)
     *   value  (Balance = u128 LE = 16 Bytes, 0 für Read-only)
     *   gasLimit (Weight = u64 LE + u64 LE = 16 Bytes, Maximale Werte)
     *   storageDepositLimit (Option<Balance> = 0x00 = None, 1 Byte)
     *   inputData (Vec<u8> = compact length + bytes)
     *
     * @param contractAddress SS58-Adresse des Contracts.
     * @param selectorHex 4-Byte ink!-Selector (0x...).
     * @return hex-encoded SCALE-Resultat (ContractResult).
     */
    public suspend fun contractsCall(
        contractAddress: String,
        selectorHex: String,
    ): String {
        val selectorBytes = hexToBytes(selectorHex)
        val contractAccountId =
            SubstrateAddress.base58Decode(contractAddress)
                ?: throw SubstrateChainAdapterException.MalformedResponse(
                    "Cannot decode SS58 address for contractsCall: $contractAddress",
                )
        val prefixLen = if (contractAccountId.size == 35) 1 else 2
        val destAccountId = contractAccountId.copyOfRange(prefixLen, prefixLen + 32)

        val args = buildScaleContractsApiCallArgs(destAccountId, selectorBytes)
        val argsHex = "0x" + args.joinToString("") { "%02x".format(it) }

        val params =
            buildJsonArray {
                add(JsonPrimitive("ContractsApi_call"))
                add(JsonPrimitive(argsHex))
            }
        val res = call("state_call", params)
        // Standard Substrate nodes return state_call result as a plain hex-string JsonPrimitive.
        // Some RPC proxies wrap it as { "result": { "Ok": { "data": "0x..." } } } or
        // { "data": "0x..." }. Handle all three forms, preferring the plain hex case first.
        if (res is JsonPrimitive) {
            return res.content
        }
        val obj =
            res as? JsonObject
                ?: throw SubstrateChainAdapterException.MalformedResponse(
                    "ContractsApi_call result is neither a hex string nor a JSON object",
                )
        val data =
            obj["result"]
                ?.let { it as? JsonObject }
                ?.get("Ok")
                ?.let { it as? JsonObject }
                ?.get("data")
                ?.jsonPrimitive
                ?.content
                ?: obj["data"]?.jsonPrimitive?.content
                ?: throw SubstrateChainAdapterException.MalformedResponse("ContractsApi_call result missing data")
        return data
    }

    /**
     * Baut die SCALE-codierten Argumente für ContractsApi_call zusammen.
     *
     * Format (vereinfacht für Read-only-Queries):
     *   origin:              32 Bytes (zufälliger AccountId, für Read-only irrelevant)
     *   dest:                32 Bytes (AccountId des Contracts)
     *   value:               16 Bytes (u128 LE, 0)
     *   gasLimit:            16 Bytes (Weight: ref_time u64 LE max + proof_size u64 LE max)
     *   storageDepositLimit: 1 Byte (Option<Balance> = 0x00 = None)
     *   inputData:           compact(len) + bytes
     */
    private fun buildScaleContractsApiCallArgs(
        destAccountId: ByteArray,
        inputData: ByteArray,
    ): ByteArray {
        val origin = ByteArray(32) { 0 } // zeroed origin for read-only call
        val value = ByteArray(16) { 0 } // u128 = 0
        // Use a high but not Long.MAX_VALUE gas limit: Long.MAX_VALUE causes dispatch errors
        // on nodes where block gas cap < Long.MAX_VALUE (which is every real Substrate node).
        // 50 billion ref_time and 5 MB proof_size are generous caps accepted by all standard runtimes.
        val gasLimitRefTime = READ_ONLY_GAS_LIMIT_REF_TIME.toLeBytes()
        val gasLimitProofSize = READ_ONLY_GAS_LIMIT_PROOF_SIZE.toLeBytes()
        val storageDepositLimit = byteArrayOf(0x00) // Option::None
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

    private fun scaleCompact(value: Int): ByteArray =
        when {
            value < 64 -> byteArrayOf((value shl 2).toByte())
            value < 16384 ->
                byteArrayOf(
                    ((value shl 2) or 1).toByte(),
                    ((value shr 6) and 0xFF).toByte(),
                )
            value < 1073741824 ->
                // Four-byte mode (SCALE mode 2): covers 16384..1073741823
                byteArrayOf(
                    ((value shl 2) or 2).toByte(),
                    ((value shr 6) and 0xFF).toByte(),
                    ((value shr 14) and 0xFF).toByte(),
                    ((value shr 22) and 0xFF).toByte(),
                )
            else -> {
                // Big-integer mode (SCALE mode 3): value >= 1073741824.
                // Encode as little-endian bytes with mode prefix (first byte = (extraBytes-4) << 2 | 3).
                // For Int values, we need at most 4 extra bytes beyond the first byte (5 bytes total
                // for values up to ~4 billion). Only positive Int values reach here (Int.MAX_VALUE
                // requires 4 extra bytes).
                val v = value.toLong() and 0xFFFFFFFFL
                val bytes = mutableListOf<Byte>()
                var remaining = v
                while (remaining > 0) {
                    bytes.add((remaining and 0xFF).toByte())
                    remaining = remaining ushr 8
                }
                val extraBytes = bytes.size
                val result = ByteArray(1 + extraBytes)
                result[0] = (((extraBytes - 4) shl 2) or 3).toByte()
                for (i in bytes.indices) result[i + 1] = bytes[i]
                result
            }
        }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    public companion object {
        /** Maximale erlaubte RPC-Response-Größe (10 MB). */
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10_485_760L

        /**
         * Gas limit for read-only ink! calls via ContractsApi_call.
         * Long.MAX_VALUE causes dispatch errors on nodes with block gas caps.
         * 50 billion ref_time and 5 MB proof_size are well within typical block limits.
         */
        public const val READ_ONLY_GAS_LIMIT_REF_TIME: Long = 50_000_000_000L
        public const val READ_ONLY_GAS_LIMIT_PROOF_SIZE: Long = 5_242_880L // 5 MB

        /** Storage-Key für System.Events (twox128("System")+twox128("Events")). */
        public const val SYSTEM_EVENTS_KEY: String =
            "0x26aa394eea5630e07c48ae0c9558cef780d41e5e16056765bc8461851072c9d7"

        /** Storage-Key für Timestamp.Now (twox128("Timestamp")+twox128("Now")). */
        public const val TIMESTAMP_NOW_KEY: String =
            "0xf0c365c3cf59d671eb72da0e7a4113c49f1f0515f462cdcf84e0f1d6045dfcbb"

        /** Erzeugt einen Standard-HttpClient mit Default-Timeouts (connect 5s). */
        public fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        /** wss/ws → https/http für die HTTP-POST-Transport-Schicht (Substrate-RPC kann beides). */
        public fun toHttpUrl(url: String): String =
            when {
                url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
                url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
                else -> url
            }

        /** Parst eine 0x-Hex-Quantity (Substrate-Header.number ist hex) → Long. */
        public fun parseHexQuantity(hex: String): Long {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return 0L
            return clean.toLongOrNull(16)
                ?: throw SubstrateChainAdapterException.MalformedResponse("Not a valid hex quantity: '$hex'")
        }

        /** Entfernt Credentials aus einer URL vor Logging/Exceptions. */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(toHttpUrl(url))
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /** Liest maximal [limit] Bytes; wirft NetworkError bei Überschreitung. */
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
                        throw SubstrateChainAdapterException.NetworkError(
                            "RPC response exceeds maximum allowed size of $limit bytes",
                        )
                    }
                    sb.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            }
            return sb.toString()
        }
    }
}
