package dev.kuml.runtime.chain.move.sui

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
 * V3.0.20 — Schlanker Sui-JSON-RPC-2.0-Client über java.net.http.HttpClient.
 *
 * Sui nutzt JSON-RPC 2.0 über POST (identische Envelope-Struktur zu EVM).
 * Sui-Quantities sind dezimale Strings (nicht 0x-Hex wie EVM).
 *
 * @property rpcUrl Endpunkt (http/https). Wird in Tests auf den lokalen HttpServer gesetzt.
 * @property maxResponseBytes Maximale Größe einer RPC-Antwort in Bytes (Default 10 MB).
 */
public class SuiRpcClient(
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
     * @param method z.B. "suix_queryEvents".
     * @param params bereits als JsonElement vorbereitete Parameterliste.
     * @return das rohe `result`-JsonElement.
     * @throws SuiChainAdapterException.RpcError bei `error`-Objekt.
     * @throws SuiChainAdapterException.NetworkError bei IO/Non-2xx.
     * @throws SuiChainAdapterException.MalformedResponse bei Schema-Verstoß.
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
                        throw SuiChainAdapterException.NetworkError(
                            "HTTP ${response.statusCode()} from ${sanitizeUrl(rpcUrl)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: SuiChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw SuiChainAdapterException.NetworkError(
                        "IO error calling ${sanitizeUrl(rpcUrl)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw SuiChainAdapterException.MalformedResponse(
                    "Could not parse JSON-RPC response: ${e.message}",
                    e,
                )
            }

        val obj =
            parsed as? JsonObject
                ?: throw SuiChainAdapterException.MalformedResponse("JSON-RPC response is not an object")

        val errorObj = obj["error"]
        if (errorObj != null && errorObj !is JsonNull) {
            val errObj =
                errorObj as? JsonObject
                    ?: throw SuiChainAdapterException.MalformedResponse("JSON-RPC error field is not an object")
            val code =
                errObj["code"]?.jsonPrimitive?.int
                    ?: throw SuiChainAdapterException.MalformedResponse("JSON-RPC error missing code")
            val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown"
            val data = errObj["data"]?.jsonPrimitive?.content
            throw SuiChainAdapterException.RpcError(code, message, data)
        }

        return obj["result"]
            ?: throw SuiChainAdapterException.MalformedResponse("JSON-RPC response missing 'result' field")
    }

    /**
     * suix_getLatestCheckpointSequenceNumber → dezimaler String → Long.
     * Sui-Quantities sind dezimale Strings (nicht 0x-Hex wie EVM).
     */
    public suspend fun getLatestCheckpointSequenceNumber(): Long {
        val result = call("suix_getLatestCheckpointSequenceNumber", buildJsonArray {})
        val s = result.jsonPrimitive.content
        return parseDecimalQuantity(s)
    }

    /**
     * sui_getCheckpoint(checkpointId) → ganzes result-Objekt (enthält timestampMs).
     */
    public suspend fun getCheckpoint(checkpointId: Long): JsonElement {
        val params = buildJsonArray { add(JsonPrimitive(checkpointId.toString())) }
        return call("sui_getCheckpoint", params)
    }

    /**
     * suix_queryEvents. cursor=null beim ersten Call.
     *
     * @param packageId Package-ID (0x + hex).
     * @param module Move-Modul-Name (z.B. "kuml").
     * @param cursor EventID-Objekt {txDigest,eventSeq} oder null für ersten Call.
     * @param limit Maximale Anzahl Events pro Page.
     * @param descending Absteigende Sortierung.
     * @return das result-Objekt mit data[], nextCursor, hasNextPage.
     */
    public suspend fun queryEvents(
        packageId: String,
        module: String,
        cursor: JsonElement?,
        limit: Int,
        descending: Boolean = false,
    ): JsonElement {
        val params =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "MoveModule",
                            buildJsonObject {
                                put("package", packageId)
                                put("module", module)
                            },
                        )
                    },
                )
                if (cursor != null) add(cursor) else add(JsonNull)
                add(JsonPrimitive(limit))
                add(JsonPrimitive(descending))
            }
        return call("suix_queryEvents", params)
    }

    /**
     * sui_getObject(objectId, {showContent:true}) → result-Objekt.
     */
    public suspend fun getObject(objectId: String): JsonElement {
        val params =
            buildJsonArray {
                add(JsonPrimitive(objectId))
                add(buildJsonObject { put("showContent", true) })
            }
        return call("sui_getObject", params)
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

        /**
         * Parst eine dezimale Sui-Quantity (String → Long).
         * Sui-Quantities sind dezimale Strings, NICHT 0x-Hex wie EVM.
         *
         * @throws SuiChainAdapterException.MalformedResponse bei Nicht-Zahl.
         */
        public fun parseDecimalQuantity(s: String): Long =
            s.toLongOrNull()
                ?: throw SuiChainAdapterException.MalformedResponse("Not a valid decimal quantity: '$s'")

        /**
         * Entfernt Credentials (userinfo) aus einer URL, bevor sie in Exception-Messages
         * oder Logs landet. Verhindert, dass API-Keys in Log-Aggregation-Systemen erscheinen.
         */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(url)
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /**
         * Liest maximal [limit] Bytes aus [stream]. Wirft [SuiChainAdapterException.NetworkError]
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
                        throw SuiChainAdapterException.NetworkError(
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
