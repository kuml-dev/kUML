package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.cosmos.Base64Codec
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
 * V3.0.21 — Schlanker Tendermint/Cosmos-JSON-RPC-2.0-Client über java.net.http.HttpClient.
 *
 * Tendermint-RPC nutzt JSON-RPC 2.0 über POST. Cosmos-spezifisch:
 * - `abci_query` mit base64-`data` für Smart-Contract-State-Queries
 *   (Pfad "/cosmwasm.wasm.v1.Query/SmartContractState" bzw. "store/wasm/key").
 * - `block_results` für wasm-Module-Events.
 * - `status` für die aktuelle Block-Höhe + Block-Zeit (RFC-3339).
 *
 * Cosmos-Nodes exponieren zwei separate Ports:
 * - Tendermint RPC: typisch :26657 (für JSON-RPC-Calls wie `status`, `block_results`)
 * - LCD REST API:   typisch :1317  (für Smart-Contract-Queries, `GET /cosmwasm/...`)
 *
 * Daher werden [rpcUrl] (für JSON-RPC) und [lcdUrl] (für LCD REST) separat übergeben.
 * Wenn [lcdUrl] null ist, wird [rpcUrl] auch für LCD-Requests verwendet (z.B. in Tests
 * mit einem einzigen MockServer).
 *
 * @property rpcUrl Tendermint-RPC-Endpunkt (http/https). Für `status`, `block_results` etc.
 * @property lcdUrl Cosmos-LCD-REST-Endpunkt (http/https). Für `smartQuery` etc. Wenn null,
 *   wird [rpcUrl] auch für LCD-Requests genutzt (nur für Tests geeignet).
 * @property maxResponseBytes Maximale Größe einer RPC-Antwort in Bytes (Default 10 MB).
 */
public class CosmWasmRpcClient(
    private val rpcUrl: String,
    private val lcdUrl: String? = null,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val idCounter = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Führt einen JSON-RPC-Call aus (suspending; blockierendes HTTP-Send auf Dispatchers.IO).
     *
     * @throws CosmWasmChainAdapterException.RpcError bei `error`-Objekt.
     * @throws CosmWasmChainAdapterException.NetworkError bei IO/non-2xx.
     * @throws CosmWasmChainAdapterException.MalformedResponse bei Schema-Verstoß.
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
                        throw CosmWasmChainAdapterException.NetworkError(
                            "HTTP ${response.statusCode()} from ${sanitizeUrl(rpcUrl)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: CosmWasmChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw CosmWasmChainAdapterException.NetworkError(
                        "IO error calling ${sanitizeUrl(rpcUrl)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw CosmWasmChainAdapterException.MalformedResponse("Could not parse JSON-RPC response: ${e.message}", e)
            }

        val obj =
            parsed as? JsonObject
                ?: throw CosmWasmChainAdapterException.MalformedResponse("JSON-RPC response is not an object")

        val errorObj = obj["error"]
        if (errorObj != null && errorObj !is JsonNull) {
            val errObj =
                errorObj as? JsonObject
                    ?: throw CosmWasmChainAdapterException.MalformedResponse("JSON-RPC error field is not an object")
            val code =
                errObj["code"]?.jsonPrimitive?.int
                    ?: throw CosmWasmChainAdapterException.MalformedResponse("JSON-RPC error missing code")
            val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown"
            val data = errObj["data"]?.jsonPrimitive?.content
            throw CosmWasmChainAdapterException.RpcError(code, message, data)
        }

        return obj["result"]
            ?: throw CosmWasmChainAdapterException.MalformedResponse("JSON-RPC response missing 'result' field")
    }

    /**
     * Smart-Query gegen einen CosmWasm-Contract via Cosmos LCD REST API.
     *
     * Nutzt den Endpunkt `GET /cosmwasm/wasm/v1/contract/{address}/smart/{base64query}`.
     * Dieser Endpunkt akzeptiert den Query als URL-safe Base64 und gibt direkt JSON zurück.
     *
     * Hintergrund: Die alternative `abci_query`-Methode mit Pfad
     * `/cosmwasm.wasm.v1.Query/SmartContractState` erwartet Protobuf-encoded Daten, NICHT
     * rohen Base64-JSON. Reale CosmWasm-Nodes lehnen falsch-encodierte abci_query-Requests ab.
     *
     * @param contractAddress Bech32-Contract-Adresse.
     * @param queryJson z.B. {"kuml_identity":{}}.
     * @return geparstes JsonElement des Query-Resultats.
     */
    public suspend fun smartQuery(
        contractAddress: String,
        queryJson: String,
    ): JsonElement {
        val queryB64 = Base64Codec.encodeUrlSafe(queryJson.toByteArray(Charsets.UTF_8))
        val lcdPath = "/cosmwasm/wasm/v1/contract/$contractAddress/smart/$queryB64"
        // Use lcdUrl if explicitly provided; fall back to rpcUrl only when lcdUrl is null
        // (e.g. in tests with a single MockServer). In production, lcdUrl and rpcUrl differ
        // because Tendermint RPC (:26657) and LCD REST (:1317) are on separate ports.
        val baseUrl = lcdUrl ?: rpcUrl
        val url = buildLcdUrl(baseUrl, lcdPath)

        val httpRequest =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(requestTimeout)
                .build()

        val responseBody =
            withContext(Dispatchers.IO) {
                try {
                    val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299) {
                        throw CosmWasmChainAdapterException.NetworkError(
                            "HTTP ${response.statusCode()} from smartQuery at ${sanitizeUrl(url)}",
                        )
                    }
                    readLimited(response.body(), maxResponseBytes)
                } catch (e: CosmWasmChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw CosmWasmChainAdapterException.NetworkError(
                        "IO error during smartQuery at ${sanitizeUrl(url)}: ${e.message}",
                        e,
                    )
                }
            }

        val parsed =
            try {
                json.parseToJsonElement(responseBody)
            } catch (e: Exception) {
                throw CosmWasmChainAdapterException.MalformedResponse("smart query response is not JSON: ${e.message}", e)
            }
        // Cosmos LCD API wraps the contract response in {"data": {...}}.
        // Unwrap the envelope so callers receive the actual contract response object.
        val parsedObj = parsed as? JsonObject
        if (parsedObj != null && parsedObj.containsKey("data")) {
            return parsedObj["data"]!!
        }
        return parsed
    }

    /** `status` → result.sync_info.latest_block_height (dezimal String → Long). */
    public suspend fun getLatestBlockHeight(): Long {
        val result = call("status", buildJsonArray {}).jsonObject
        val syncInfo =
            result["sync_info"]?.jsonObject
                ?: throw CosmWasmChainAdapterException.MalformedResponse("status missing 'sync_info'")
        val h =
            syncInfo["latest_block_height"]?.jsonPrimitive?.content
                ?: throw CosmWasmChainAdapterException.MalformedResponse("sync_info missing 'latest_block_height'")
        return h.toLongOrNull()
            ?: throw CosmWasmChainAdapterException.MalformedResponse("latest_block_height not a long: '$h'")
    }

    /** `status` → result.sync_info → {height, time}-Header für die BlockClock. */
    public suspend fun getLatestBlockHeader(): JsonObject {
        val result = call("status", buildJsonArray {}).jsonObject
        val syncInfo =
            result["sync_info"]?.jsonObject
                ?: throw CosmWasmChainAdapterException.MalformedResponse("status missing 'sync_info'")
        return buildJsonObject {
            syncInfo["latest_block_height"]?.let { put("height", it.jsonPrimitive.content) }
            syncInfo["latest_block_time"]?.let { put("time", it.jsonPrimitive.content) }
        }
    }

    /**
     * `block_results` für eine Höhe → result-Objekt mit `txs_results[].events` und `finalize_block_events`.
     *
     * @param height Block-Höhe (dezimal).
     */
    public suspend fun getBlockResults(height: Long): JsonElement {
        val params = buildJsonObject { put("height", JsonPrimitive(height.toString())) }
        return call("block_results", params)
    }

    public companion object {
        /** Maximale erlaubte RPC-Response-Größe (10 MB). Schützt vor Heap-Exhaustion. */
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10_485_760L

        /** Erzeugt einen Standard-HttpClient mit Default-Timeouts (connect 5s). */
        public fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        /**
         * Baut eine LCD-REST-URL aus dem RPC-Basis-URL und einem Pfad.
         * Nimmt den Schema+Host+Port des rpcUrl und hängt [lcdPath] an.
         */
        internal fun buildLcdUrl(
            rpcUrl: String,
            lcdPath: String,
        ): String =
            try {
                val uri = URI(rpcUrl)
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$port$lcdPath"
            } catch (_: Exception) {
                rpcUrl.trimEnd('/') + lcdPath
            }

        /** Entfernt Credentials (userinfo) aus einer URL vor Logging/Exceptions. */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(url)
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /**
         * Liest maximal [limit] Bytes aus [stream]. Wirft [CosmWasmChainAdapterException.NetworkError]
         * bei Überschreitung, um Heap-Exhaustion zu verhindern.
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
                        throw CosmWasmChainAdapterException.NetworkError(
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
