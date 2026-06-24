package dev.kuml.runtime.chain.cosmos.cosmwasm

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * V3.0.21 — URL-Validierungsstrategie für CosmWasm-RPC-Endpunkte.
 *
 * [Default] führt vollständige SSRF-Prüfung durch (Schema-Whitelist + IP-Range-Blocklist).
 * [NoOp] akzeptiert alle URLs — nur für Tests mit lokalem MockServer verwenden.
 */
public fun interface CosmWasmUrlValidator {
    /**
     * Validiert [rpcUrl]. Wirft [CosmWasmChainAdapterException.InvalidUrlException] bei
     * SSRF-Verstoss oder ungültigem Format.
     */
    public fun validate(rpcUrl: String)

    public companion object {
        /**
         * Erlaubte Schemas für [Default]: nur HTTPS (kein HTTP).
         * Unverschlüsselte HTTP-Verbindungen können kryptografisches Material (Modell-Hashes,
         * Contract-Identitäten) per MITM-Angriff offenlegen.
         */
        private val PRODUCTION_SCHEMES = setOf("https")

        /**
         * Erlaubte Schemas für [Staging]: HTTPS und HTTP.
         * Nur für Staging/CI-Umgebungen ohne öffentlichen Zugriff geeignet.
         */
        private val STAGING_SCHEMES = setOf("https", "http")

        /**
         * Produktion: vollständige SSRF-Validierung (Schema-Whitelist HTTPS-only + DNS-Auflösung +
         * IP-Range-Blocklist für alle aufgelösten Adressen).
         *
         * Alle aufgelösten IPs werden geprüft — nicht nur IP-Literals. Das verhindert
         * SSRF über Hostnamen die zu privaten/Loopback-Adressen auflösen (z.B. via
         * DNS-Rebinding oder Split-Horizon-DNS).
         */
        public val Default: CosmWasmUrlValidator = buildValidator(allowedSchemes = PRODUCTION_SCHEMES)

        /**
         * Staging/CI: SSRF-Validierung mit HTTP+HTTPS-Schema-Whitelist.
         *
         * Geeignet für interne Staging-Umgebungen, die HTTP verwenden.
         * WARNUNG: HTTP überträgt Daten unverschlüsselt — nur für Netzwerke ohne
         * öffentlichen Zugriff verwenden. Niemals in Produktion einsetzen.
         */
        public val Staging: CosmWasmUrlValidator = buildValidator(allowedSchemes = STAGING_SCHEMES)

        /** Test-only: akzeptiert alle URLs (localhost, private IPs). */
        public val NoOp: CosmWasmUrlValidator = CosmWasmUrlValidator { /* no-op */ }

        private fun buildValidator(allowedSchemes: Set<String>): CosmWasmUrlValidator =
            CosmWasmUrlValidator { url ->
                val uri =
                    try {
                        URI(url)
                    } catch (e: Exception) {
                        throw CosmWasmChainAdapterException.InvalidUrlException(
                            "rpcUrl is not a valid URI: '$url'",
                        )
                    }
                if (uri.scheme !in allowedSchemes) {
                    throw CosmWasmChainAdapterException.InvalidUrlException(
                        "rpcUrl scheme must be one of $allowedSchemes, got '${uri.scheme}'",
                    )
                }
                val host =
                    uri.host
                        ?: throw CosmWasmChainAdapterException.InvalidUrlException("rpcUrl has no host: '$url'")

                // Resolve ALL addresses (hostname or IP literal) and check each for private ranges.
                val addrs =
                    try {
                        InetAddress.getAllByName(host)
                    } catch (_: Exception) {
                        throw CosmWasmChainAdapterException.InvalidUrlException(
                            "rpcUrl host cannot be resolved: '$host'",
                        )
                    }
                if (addrs.isEmpty()) {
                    throw CosmWasmChainAdapterException.InvalidUrlException(
                        "rpcUrl host resolved to no addresses: '$host'",
                    )
                }
                for (addr in addrs) {
                    if (isPrivateOrLoopback(addr)) {
                        throw CosmWasmChainAdapterException.InvalidUrlException(
                            "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection): '$host' resolved to ${addr.hostAddress}",
                        )
                    }
                }
            }

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
