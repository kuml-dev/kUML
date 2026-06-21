package dev.kuml.runtime.chain.move.aptos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * V3.0.20 — Schlanker Aptos-REST-Client über java.net.http.HttpClient.
 *
 * Aptos nutzt REST/GET (kein JSON-RPC). Kein Envelope, direkte JSON-Bodies.
 * Type-Tags (z.B. "0x1::kuml::Registry") werden URL-encoded, da sie "::" enthalten.
 *
 * @property baseUrl Aptos-Fullnode-Basis-URL (z.B. "https://fullnode.mainnet.aptoslabs.com").
 * @property maxResponseBytes Maximale Größe einer API-Antwort in Bytes (Default 10 MB).
 */
public class AptosRestClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generischer GET-Call (suspending; blockierendes HTTP-Send auf Dispatchers.IO).
     *
     * @param path Pfad beginnend mit "/v1/..." (wird an baseUrl angehängt).
     * @return geparstes JsonElement.
     * @throws AptosChainAdapterException.ApiError bei HTTP-Fehler-Status.
     * @throws AptosChainAdapterException.NetworkError bei IO-Fehler.
     * @throws AptosChainAdapterException.MalformedResponse bei JSON-Parse-Fehler.
     */
    public suspend fun get(path: String): kotlinx.serialization.json.JsonElement {
        val url = baseUrl.trimEnd('/') + path
        val req =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(requestTimeout)
                .build()

        val body =
            withContext(Dispatchers.IO) {
                try {
                    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
                    if (resp.statusCode() !in 200..299) {
                        val errBody =
                            runCatching { readLimited(resp.body(), maxResponseBytes) }.getOrDefault("")
                        val msg =
                            runCatching {
                                json
                                    .parseToJsonElement(errBody)
                                    .jsonObject["message"]
                                    ?.jsonPrimitive
                                    ?.content
                            }.getOrNull() ?: "HTTP ${resp.statusCode()}"
                        throw AptosChainAdapterException.ApiError(resp.statusCode(), msg)
                    }
                    readLimited(resp.body(), maxResponseBytes)
                } catch (e: AptosChainAdapterException) {
                    throw e
                } catch (e: Exception) {
                    throw AptosChainAdapterException.NetworkError(
                        "IO error calling ${sanitizeUrl(url)}: ${e.message}",
                        e,
                    )
                }
            }

        return try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw AptosChainAdapterException.MalformedResponse(
                "Could not parse Aptos response: ${e.message}",
                e,
            )
        }
    }

    /** GET /v1/ → Ledger-Info-Objekt (enthält ledger_version, block_height, ledger_timestamp). */
    public suspend fun getLedgerInfo(): JsonObject = get("/v1/").jsonObject

    /**
     * GET /v1/blocks/by_height/{height}?with_transactions=false → Block-Objekt.
     * Enthält block_timestamp in **Mikrosekunden**.
     */
    public suspend fun getBlockByHeight(height: Long): JsonObject = get("/v1/blocks/by_height/$height?with_transactions=false").jsonObject

    /**
     * GET /v1/accounts/{address}/events/{eventHandleStruct}/{fieldName}?start={start}&limit={limit}
     *
     * Type-Tags mit "::" werden URL-encoded.
     *
     * @param address Account-Adresse (0x + hex).
     * @param eventHandleStruct Event-Handle-Struct-Type-Tag (z.B. "0x1::kuml::Registry").
     * @param fieldName Feldname des Event-Handles (z.B. "model_events").
     * @param start Sequenznummer des ersten Events.
     * @param limit Maximale Anzahl Events.
     * @return JsonArray von Event-Objekten.
     */
    public suspend fun getEvents(
        address: String,
        eventHandleStruct: String,
        fieldName: String,
        start: Long,
        limit: Int,
    ): JsonArray {
        val encStruct = URLEncoder.encode(eventHandleStruct, StandardCharsets.UTF_8)
        return get("/v1/accounts/$address/events/$encStruct/$fieldName?start=$start&limit=$limit").jsonArray
    }

    /**
     * GET /v1/accounts/{address}/resource/{typeTag}
     * Type-Tags mit "::" werden URL-encoded.
     *
     * @return Ressource-Objekt (enthält "type" und "data"-Felder).
     */
    public suspend fun getResource(
        address: String,
        typeTag: String,
    ): JsonObject {
        val enc = URLEncoder.encode(typeTag, StandardCharsets.UTF_8)
        return get("/v1/accounts/$address/resource/$enc").jsonObject
    }

    public companion object {
        /** Maximale erlaubte API-Response-Größe (10 MB). Schützt vor Heap-Exhaustion. */
        public const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10_485_760L

        /** Erzeugt einen Standard-HttpClient mit Default-Timeouts. */
        public fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        /**
         * Entfernt Credentials (userinfo) aus einer URL, bevor sie in Exception-Messages
         * oder Logs landet.
         */
        public fun sanitizeUrl(url: String): String =
            try {
                val uri = URI(url)
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                url.substringBefore("@").substringBefore("?").take(64)
            }

        /**
         * Liest maximal [limit] Bytes aus [stream]. Wirft [AptosChainAdapterException.NetworkError]
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
                        throw AptosChainAdapterException.NetworkError(
                            "API response exceeds maximum allowed size of $limit bytes",
                        )
                    }
                    sb.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            }
            return sb.toString()
        }
    }
}
