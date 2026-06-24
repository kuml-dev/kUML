package dev.kuml.runtime.chain.cosmos.substrate

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * V3.0.21 — URL-Validierungsstrategie für Substrate-RPC-Endpunkte.
 *
 * [Default] führt vollständige SSRF-Prüfung durch (Schema-Whitelist: https/wss + IP-Range-Blocklist).
 * [NoOp] akzeptiert alle URLs — nur für Tests mit lokalem MockServer verwenden.
 */
public fun interface SubstrateUrlValidator {
    /**
     * Validiert [rpcUrl]. Wirft [SubstrateChainAdapterException.InvalidUrlException] bei
     * SSRF-Verstoss oder ungültigem Format.
     */
    public fun validate(rpcUrl: String)

    public companion object {
        /**
         * Erlaubte Schemas: nur HTTPS und WSS (kein HTTP oder WS).
         * Unverschlüsselte Verbindungen können kryptografisches Material per MITM-Angriff offenlegen.
         */
        private val ALLOWED_SCHEMES = setOf("https", "wss")

        /**
         * Produktion: vollständige SSRF-Validierung (Schema-Whitelist + DNS-Auflösung +
         * IP-Range-Blocklist für alle aufgelösten Adressen).
         *
         * Alle aufgelösten IPs werden geprüft — nicht nur IP-Literals. Das verhindert
         * SSRF über Hostnamen die zu privaten/Loopback-Adressen auflösen (z.B. via
         * DNS-Rebinding oder Split-Horizon-DNS).
         */
        public val Default: SubstrateUrlValidator =
            SubstrateUrlValidator { url ->
                val httpUrl = SubstrateRpcClient.toHttpUrl(url)
                val uri =
                    try {
                        URI(httpUrl)
                    } catch (e: Exception) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl is not a valid URI: '$url'",
                        )
                    }
                val origScheme = URI(url).scheme ?: ""
                if (origScheme !in ALLOWED_SCHEMES) {
                    throw SubstrateChainAdapterException.InvalidUrlException(
                        "rpcUrl must use https or wss, got '$origScheme'",
                    )
                }
                val host =
                    uri.host
                        ?: throw SubstrateChainAdapterException.InvalidUrlException("rpcUrl has no host: '$url'")

                // Resolve ALL addresses (hostname or IP literal) and check each for private ranges.
                val addrs =
                    try {
                        InetAddress.getAllByName(host)
                    } catch (_: Exception) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl host cannot be resolved: '$host'",
                        )
                    }
                if (addrs.isEmpty()) {
                    throw SubstrateChainAdapterException.InvalidUrlException(
                        "rpcUrl host resolved to no addresses: '$host'",
                    )
                }
                for (addr in addrs) {
                    if (isPrivateOrLoopback(addr)) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection): '$host' resolved to ${addr.hostAddress}",
                        )
                    }
                }
            }

        /**
         * Staging/CI: SSRF-Validierung mit HTTP+HTTPS+WS+WSS-Schema-Whitelist.
         *
         * Geeignet für interne Staging-Umgebungen ohne TLS.
         * WARNUNG: HTTP/WS übertragen Daten unverschlüsselt — nur für Netzwerke ohne
         * öffentlichen Zugriff verwenden. Niemals in Produktion einsetzen.
         */
        public val Staging: SubstrateUrlValidator =
            SubstrateUrlValidator { url ->
                val httpUrl = SubstrateRpcClient.toHttpUrl(url)
                val uri =
                    try {
                        URI(httpUrl)
                    } catch (e: Exception) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl is not a valid URI: '$url'",
                        )
                    }
                val origScheme = URI(url).scheme ?: ""
                if (origScheme !in setOf("https", "wss", "http", "ws")) {
                    throw SubstrateChainAdapterException.InvalidUrlException(
                        "rpcUrl must use https, wss, http, or ws, got '$origScheme'",
                    )
                }
                val host =
                    uri.host
                        ?: throw SubstrateChainAdapterException.InvalidUrlException("rpcUrl has no host: '$url'")
                val addrs =
                    try {
                        InetAddress.getAllByName(host)
                    } catch (_: Exception) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl host cannot be resolved: '$host'",
                        )
                    }
                if (addrs.isEmpty()) {
                    throw SubstrateChainAdapterException.InvalidUrlException(
                        "rpcUrl host resolved to no addresses: '$host'",
                    )
                }
                for (addr in addrs) {
                    if (isPrivateOrLoopback(addr)) {
                        throw SubstrateChainAdapterException.InvalidUrlException(
                            "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection): '$host' resolved to ${addr.hostAddress}",
                        )
                    }
                }
            }

        /** Test-only: akzeptiert alle URLs (localhost, private IPs). */
        public val NoOp: SubstrateUrlValidator = SubstrateUrlValidator { /* no-op */ }

        private fun isPrivateOrLoopback(addr: InetAddress): Boolean =
            addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress ||
                // JVM's isSiteLocalAddress() covers only the deprecated fec0::/10 range for IPv6.
                // Explicitly block the current ULA range fc00::/7 (RFC 4193).
                (addr is Inet6Address && (addr.address[0].toInt() and 0xFE) == 0xFC)
    }
}
