package dev.kuml.ai.spi

import java.net.InetAddress

/**
 * Validates a `baseUrl` supplied via [KumlLlmProviderSpi.buildClient]'s `options` map.
 *
 * Rules enforced:
 * 1. Scheme must be `https` — except when [isLocalProvider] is `true`, in which case
 *    `http://localhost` (and `http://127.0.0.1` / `http://[::1]`) is also accepted.
 *    This accommodates Ollama-style local providers that don't terminate TLS.
 * 2. The resolved host must not be a private or loopback address (SSRF protection):
 *    - Loopback: 127.0.0.0/8, ::1
 *    - RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 *    - Link-local: 169.254.0.0/16, fe80::/10
 *    - Unspecified/wildcard: 0.0.0.0, :: (isAnyLocalAddress — on Linux these reach localhost)
 *    Exception: loopback is allowed when [isLocalProvider] is `true`.
 *
 * @param url The URL string to validate (typically `options["baseUrl"]`).
 * @param isLocalProvider Whether this provider is declared as a local-only provider.
 * @throws IllegalArgumentException if the URL violates any of the rules above.
 */
public fun validateBaseUrl(
    url: String,
    isLocalProvider: Boolean,
) {
    val parsed =
        runCatching { java.net.URI(url) }.getOrElse {
            throw IllegalArgumentException("baseUrl is not a valid URI: $url", it)
        }

    val scheme = parsed.scheme?.lowercase() ?: throw IllegalArgumentException("baseUrl has no scheme: $url")
    val host = parsed.host ?: throw IllegalArgumentException("baseUrl has no host: $url")

    if (scheme != "https") {
        if (isLocalProvider && scheme == "http") {
            // Allow http for local providers — but still block if the host resolves to a
            // non-local address (someone could pass http://evil.example.com).
            // Loopback / localhost check happens below.
        } else {
            throw IllegalArgumentException(
                "baseUrl must use https scheme (got '$scheme'): $url",
            )
        }
    }

    // Resolve the host and check for private/loopback ranges.
    val resolved =
        runCatching { InetAddress.getByName(host) }.getOrElse {
            throw IllegalArgumentException("baseUrl host cannot be resolved: $host", it)
        }

    val isLoopback = resolved.isLoopbackAddress
    val isLinkLocal = resolved.isLinkLocalAddress
    val isPrivate = isPrivateRange(resolved)

    val isAnyLocal = resolved.isAnyLocalAddress

    if (isLocalProvider) {
        // Local providers may only bind to loopback — not to RFC-1918, link-local, or
        // wildcard/unspecified (0.0.0.0 / ::) ranges (those could reach devices on the
        // LAN or behave like loopback on Linux, which counts as SSRF).
        if (!isLoopback || isAnyLocal) {
            throw IllegalArgumentException(
                "baseUrl for a local provider must resolve to a loopback address (127.x.x.x / ::1). " +
                    "Got: $host → ${resolved.hostAddress}",
            )
        }
    } else {
        // Remote providers must not resolve to any private, loopback, link-local, or
        // unspecified/wildcard address (0.0.0.0 / :: — isAnyLocalAddress covers these;
        // on Linux, outbound connections to 0.0.0.0 reach localhost, bypassing the
        // isLoopbackAddress check).
        if (isLoopback || isLinkLocal || isPrivate || isAnyLocal) {
            throw IllegalArgumentException(
                "baseUrl must not resolve to a private, loopback, link-local, or unspecified address " +
                    "(SSRF prevention). Got: $host → ${resolved.hostAddress}",
            )
        }
    }
}

/** Returns true if [address] falls within an RFC-1918 private range. */
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

/**
 * Service Provider Interface for custom kUML LLM providers.
 *
 * Third-party modules implement this interface and register their implementation via
 * `META-INF/services/dev.kuml.ai.spi.KumlLlmProviderSpi` to have their provider
 * discovered by `ProviderRegistry.discover()`.
 *
 * **Collision rule (V3.1.15):** if a custom provider's [id] matches a built-in
 * provider id ("openai", "anthropic", "google", "ollama"), the built-in wins and
 * the custom provider is silently ignored with a warning log.
 *
 * **Why [buildClient] returns [Any]:** this artifact intentionally has zero Koog
 * dependency so third parties can implement the SPI without a Koog transitive
 * dependency. Implementors return a Koog `LLMClient` typed as [Any]; kuml-ai-core
 * adapts it at the registry boundary. A class-cast exception at that point means
 * the implementor returned the wrong type.
 */
public interface KumlLlmProviderSpi {
    /**
     * Stable lowercase identifier — must be unique within the registry.
     * Examples: "openai", "my-custom-provider".
     */
    public val id: String

    /** Human-readable display name for CLI tables and UI. */
    public val displayName: String

    /**
     * `true` if this provider runs locally and makes no third-party network calls.
     * Local providers do not require an API key.
     */
    public val isLocalProvider: Boolean

    /**
     * Models this provider advertises.
     *
     * Return an empty list if the provider accepts dynamic model ids (e.g. Ollama).
     * Entries are shown in `kuml ai provider info <id>`.
     */
    public fun supportedModels(): List<ModelDescriptor>

    /**
     * Build the underlying Koog `LLMClient` for this provider.
     *
     * The return type is [Any] to keep this artifact Koog-free — implementors
     * must return a Koog `LLMClient` instance or a compatible adapter.
     *
     * **Security requirement — implementors MUST call [validateBaseUrl] before using
     * `options["baseUrl"]` to construct an HTTP client.** Skipping this check opens
     * the door to Server-Side Request Forgery (SSRF): a user-supplied base URL could
     * redirect traffic to RFC-1918 / loopback addresses on the local network.
     * Example safe usage:
     * ```kotlin
     * val base = options["baseUrl"] ?: DEFAULT_BASE_URL
     * validateBaseUrl(base, isLocalProvider)   // throws IllegalArgumentException on violation
     * // … build your Ktor/OkHttp client using `base` …
     * ```
     *
     * @param apiKey The resolved API key, or an empty string for local providers.
     * @param options Free-form provider-tuning map (baseUrl, org-id, timeouts, …).
     *   When `baseUrl` is present, pass it through [validateBaseUrl] before use.
     */
    public fun buildClient(
        apiKey: String,
        options: Map<String, String>,
    ): Any
}
