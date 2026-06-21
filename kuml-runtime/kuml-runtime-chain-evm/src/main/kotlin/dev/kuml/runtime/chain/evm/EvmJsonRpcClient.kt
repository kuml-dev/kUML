package dev.kuml.runtime.chain.evm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
 * Schlanker Ethereum-JSON-RPC-2.0-Client über java.net.http.HttpClient.
 *
 * Stateless bzgl. Chain-Logik; serialisiert {jsonrpc, id, method, params} und
 * gibt das rohe `result`-[JsonElement] zurück. Ein `error`-Objekt →
 * [EvmChainAdapterException.RpcError].
 *
 * @property rpcUrl Endpunkt (http/https). Wird in Tests auf den lokalen HttpServer gesetzt.
 * @property maxResponseBytes Maximale Größe einer RPC-Antwort in Bytes (Default 10 MB).
 *   Schützt vor DoS durch überdimensionierte Antworten (z.B. eth_getLogs über breite Block-Ranges).
 */
public class EvmJsonRpcClient(
    private val rpcUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val idCounter = AtomicLong(1L)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Führt einen einzelnen JSON-RPC-Call aus (suspending; blockierendes HTTP-Send
     * auf Dispatchers.IO ausgelagert).
     *
     * @param method z.B. "eth_blockNumber".
     * @param params bereits als JsonElement vorbereitete Parameterliste.
     * @return das rohe `result`-JsonElement.
     * @throws EvmChainAdapterException.RpcError bei `error`-Objekt.
     * @throws EvmChainAdapterException.NetworkError bei IO/Non-2xx.
     * @throws EvmChainAdapterException.MalformedResponse bei Schema-Verstoß.
     */
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
                .uri(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(requestTimeout)
                .build()

        val responseBody =
            withContext(Dispatchers.IO) {
                try {
                    val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299) {
                        throw EvmChainAdapterException.NetworkError(
                            "HTTP ${response.statusCode()} from ${sanitizeUrl(rpcUrl)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: EvmChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw EvmChainAdapterException.NetworkError(
                        "IO error calling ${sanitizeUrl(rpcUrl)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw EvmChainAdapterException.MalformedResponse(
                    "Could not parse JSON-RPC response: ${e.message}",
                    e,
                )
            }

        val obj =
            parsed as? JsonObject
                ?: throw EvmChainAdapterException.MalformedResponse("JSON-RPC response is not an object")

        val errorObj = obj["error"]
        if (errorObj != null && errorObj !is JsonNull) {
            val errObj =
                errorObj as? JsonObject
                    ?: throw EvmChainAdapterException.MalformedResponse("JSON-RPC error field is not an object")
            val code =
                errObj["code"]?.jsonPrimitive?.int
                    ?: throw EvmChainAdapterException.MalformedResponse("JSON-RPC error missing code")
            val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown"
            val data = errObj["data"]?.jsonPrimitive?.content
            throw EvmChainAdapterException.RpcError(code, message, data)
        }

        return obj["result"]
            ?: throw EvmChainAdapterException.MalformedResponse("JSON-RPC response missing 'result' field")
    }

    /** Liest die aktuelle Block-Höhe als Long (eth_blockNumber). */
    public suspend fun ethBlockNumber(): Long {
        val result = call("eth_blockNumber", buildJsonArray {})
        val hex = result.jsonPrimitive.content
        return parseHexQuantity(hex)
    }

    /** Liest einen Block nach Nummer; [fullTx]=false liefert nur Tx-Hashes. */
    public suspend fun ethGetBlockByNumber(
        blockNumber: Long,
        fullTx: Boolean = false,
    ): JsonElement {
        val params =
            buildJsonArray {
                add(JsonPrimitive(toHexQuantity(blockNumber)))
                add(JsonPrimitive(fullTx))
            }
        return call("eth_getBlockByNumber", params)
    }

    /** Liest einen Block per finality-Tag ("finalized", "latest", "safe"). */
    public suspend fun ethGetBlockByTag(
        tag: String,
        fullTx: Boolean = false,
    ): JsonElement {
        val params =
            buildJsonArray {
                add(JsonPrimitive(tag))
                add(JsonPrimitive(fullTx))
            }
        return call("eth_getBlockByNumber", params)
    }

    /** Holt Event-Logs für einen Adress- und Block-Range-Filter. */
    public suspend fun ethGetLogs(
        address: String,
        fromBlock: Long,
        toBlock: Long,
        topics: List<String?> = emptyList(),
    ): JsonElement {
        val filterObj =
            buildJsonObject {
                put("address", address)
                put("fromBlock", toHexQuantity(fromBlock))
                put("toBlock", toHexQuantity(toBlock))
                if (topics.isNotEmpty()) {
                    put(
                        "topics",
                        JsonArray(
                            topics.map { t ->
                                if (t == null) JsonNull else JsonPrimitive(t)
                            },
                        ),
                    )
                }
            }
        val params = buildJsonArray { add(filterObj) }
        return call("eth_getLogs", params)
    }

    /** Führt einen read-only eth_call aus und gibt den 0x-Hex-String zurück. */
    public suspend fun ethCall(
        to: String,
        data: String,
        blockTag: String = "latest",
    ): String {
        val callObj =
            buildJsonObject {
                put("to", to)
                put("data", data)
            }
        val params =
            buildJsonArray {
                add(callObj)
                add(JsonPrimitive(blockTag))
            }
        val result = call("eth_call", params)
        return result.jsonPrimitive.content
    }

    public companion object {
        /** Maximale erlaubte RPC-Response-Größe (10 MB). Schützt vor Heap-Exhaustion. */
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10_485_760L

        /** Erzeugt einen Standard-HttpClient mit Default-Timeouts. */
        public fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        /** Parst "0x1a" → 26L. Wirft MalformedResponse bei Nicht-Hex. */
        public fun parseHexQuantity(hex: String): Long {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return 0L
            return try {
                java.lang.Long.parseUnsignedLong(clean, 16)
            } catch (e: NumberFormatException) {
                throw EvmChainAdapterException.MalformedResponse("Not a valid hex quantity: '$hex'", e)
            }
        }

        /** Long → "0x..."-Quantity (minimal, kein Leading-Zero außer "0x0" für 0). */
        public fun toHexQuantity(value: Long): String {
            if (value == 0L) return "0x0"
            return "0x" + java.lang.Long.toUnsignedString(value, 16)
        }

        /**
         * Entfernt Credentials (userinfo) aus einer URL, bevor sie in Exception-Messages
         * oder Logs landet. Verhindert, dass API-Keys in Log-Aggregation-Systemen erscheinen.
         * Beispiel: "https://user:apikey@node.example.com/rpc" → "https://node.example.com/rpc"
         */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(url)
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                // Falls die URL nicht parst, nur Schema+erstes Segment zeigen
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /**
         * Liest maximal [limit] Bytes aus [stream]. Wirft [EvmChainAdapterException.NetworkError]
         * wenn der Stream mehr Daten enthält, um Heap-Exhaustion zu verhindern.
         */
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
                        throw EvmChainAdapterException.NetworkError(
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
