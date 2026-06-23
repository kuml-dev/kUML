package dev.kuml.ai.pricing

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Strategy for fetching live pricing JSON from a remote endpoint.
 *
 * Implementations must never throw — they return `null` on any failure,
 * allowing callers to fall back to bundled pricing gracefully.
 *
 * Declared as `fun interface` to allow SAM conversions in test code (e.g. `PricingFetcher { null }`).
 */
public fun interface PricingFetcher {
    /**
     * Attempt to fetch raw pricing JSON.
     * Returns the JSON body string on success, or `null` on any failure
     * (network error, non-2xx, body-too-large, etc.).
     */
    public fun fetch(): String?
}

/**
 * HTTPS-only fetcher backed by the JDK 21 [HttpClient] — no third-party HTTP dependency.
 *
 * Security controls (per kUML SSRF hardening):
 *  - Scheme must be `https` — rejects `http`, `file`, etc.
 *  - Target hostname is resolved via [InetAddress] before the request is sent; loopback,
 *    link-local, wildcard, and RFC 1918 addresses are rejected (DNS-rebinding guard).
 *  - The same hostname check is repeated on the final URI after any redirect.
 *  - Response body capped at [maxBytes] (default 64 KB) to prevent DoS.
 *  - Hard connection + request timeout of [timeout] (default 5 s).
 *  - Redirects follow only HTTPS destinations (`Redirect.NORMAL` with post-redirect scheme check).
 *  - Any exception silently returns `null`.
 */
internal class HttpsPricingFetcher(
    private val url: String = "https://kuml.dev/api/pricing.json",
    private val timeout: Duration = Duration.ofSeconds(5),
    private val maxBytes: Int = 64 * 1024,
) : PricingFetcher {
    private val client: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    override fun fetch(): String? =
        runCatching {
            val uri = URI.create(url)
            // SSRF guard 1: only allow HTTPS scheme
            if (uri.scheme?.lowercase() != "https") return@runCatching null

            // SSRF guard 2: reject private/loopback/link-local targets (DNS-rebinding defence)
            val resolved = InetAddress.getByName(uri.host)
            if (resolved.isLoopbackAddress ||
                resolved.isLinkLocalAddress ||
                resolved.isAnyLocalAddress ||
                isPrivateRange(resolved)
            ) {
                return@runCatching null
            }

            val request =
                HttpRequest
                    .newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", "kuml-cli")
                    .GET()
                    .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            // SSRF guard 3: re-check scheme of final URI after redirect
            if (response.uri().scheme?.lowercase() != "https") return@runCatching null

            // SSRF guard 4: re-check resolved address of final URI after redirect
            val redirectHost = response.uri().host
            if (redirectHost != null) {
                val redirectResolved = InetAddress.getByName(redirectHost)
                if (redirectResolved.isLoopbackAddress ||
                    redirectResolved.isLinkLocalAddress ||
                    redirectResolved.isAnyLocalAddress ||
                    isPrivateRange(redirectResolved)
                ) {
                    return@runCatching null
                }
            }

            if (response.statusCode() !in 200..299) return@runCatching null

            val bytes = response.body()
            // DoS: body size cap
            if (bytes.size > maxBytes) return@runCatching null

            bytes.toString(Charsets.UTF_8)
        }.getOrNull()
}

/** Returns true if [address] falls within an RFC-1918 private or link-local range. */
private fun isPrivateRange(address: InetAddress): Boolean {
    val raw = address.address
    if (raw.size == 4) {
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 169 && b1 == 254) // link-local (also covered by isLinkLocalAddress but explicit here)
    }
    // IPv6 fc00::/7 (unique-local)
    if (raw.size == 16) {
        val b0 = raw[0].toInt() and 0xFF
        return (b0 and 0xFE) == 0xFC
    }
    return false
}

/** Test-double that returns a fixed JSON string (or null to simulate failure). */
internal class StubPricingFetcher(
    private val json: String?,
) : PricingFetcher {
    override fun fetch(): String? = json
}
