package dev.kuml.runtime.chain.wasm.rpc

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * V3.0.22 — URL-Validierungsstrategie fuer Substrate-WASM-RPC-Endpunkte.
 *
 * [Default] fuehrt vollstaendige SSRF-Pruefung durch:
 * - Schema-Whitelist: https und http (wss/ws werden per [toHttpUrl] auf https/http abgebildet)
 * - DNS-Aufloesung + IP-Range-Blocklist fuer alle aufgeloesten Adressen
 *   (RFC 1918 Private, Loopback, APIPA/Link-Local, IPv6 ::1 und ULA fc00::/7)
 *
 * [NoOp] akzeptiert alle URLs — ausschliesslich fuer Tests mit lokalem MockServer verwenden.
 */
public fun interface SubstrateRpcUrlValidator {
    /**
     * Validiert [rpcUrl]. Wirft [SubstrateWasmException.InvalidUrl] bei
     * SSRF-Verstoss oder ungueltigem Format.
     */
    public fun validate(rpcUrl: String)

    public companion object {
        /**
         * Erlaubte Schemas (nach ws/wss → http/https Normalisierung): https und http.
         * Substrat-RPC-Endpunkte koennen wss:// oder https:// sein; beide werden akzeptiert.
         */
        private val ALLOWED_HTTP_SCHEMES = setOf("https", "http")

        /**
         * Produktion: vollstaendige SSRF-Validierung.
         *
         * Alle aufgeloesten IPs werden geprueft — nicht nur IP-Literals. Das verhindert
         * SSRF ueber Hostnamen die zu privaten/Loopback-Adressen aufloesen (z.B. via
         * DNS-Rebinding oder Split-Horizon-DNS).
         */
        public val Default: SubstrateRpcUrlValidator =
            SubstrateRpcUrlValidator { url ->
                val httpUrl = SubstrateRpcClient.toHttpUrl(url)
                val uri =
                    try {
                        URI(httpUrl)
                    } catch (e: Exception) {
                        throw SubstrateWasmException.InvalidUrl(
                            "rpcUrl is not a valid URI: '$url'",
                        )
                    }
                val scheme = uri.scheme ?: ""
                if (scheme !in ALLOWED_HTTP_SCHEMES) {
                    throw SubstrateWasmException.InvalidUrl(
                        "rpcUrl must use https, http, wss, or ws, got '$scheme'",
                    )
                }
                val host =
                    uri.host
                        ?: throw SubstrateWasmException.InvalidUrl("rpcUrl has no host: '$url'")

                val addrs =
                    try {
                        InetAddress.getAllByName(host)
                    } catch (_: Exception) {
                        throw SubstrateWasmException.InvalidUrl(
                            "rpcUrl host cannot be resolved: '$host'",
                        )
                    }
                if (addrs.isEmpty()) {
                    throw SubstrateWasmException.InvalidUrl(
                        "rpcUrl host resolved to no addresses: '$host'",
                    )
                }
                for (addr in addrs) {
                    if (isPrivateOrLoopback(addr)) {
                        throw SubstrateWasmException.InvalidUrl(
                            "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection): " +
                                "'$host' resolved to ${addr.hostAddress}",
                        )
                    }
                }
            }

        /** Test-only: akzeptiert alle URLs (localhost, private IPs). */
        public val NoOp: SubstrateRpcUrlValidator = SubstrateRpcUrlValidator { /* no-op */ }

        private fun isPrivateOrLoopback(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress
            ) {
                return true
            }
            if (addr is Inet6Address) {
                val b = addr.address
                // ULA-Range fc00::/7 (RFC 4193) — JVM's isSiteLocalAddress() deckt nur fec0::/10 ab.
                if ((b[0].toInt() and 0xFE) == 0xFC) return true
                // IPv4-mapped IPv6 (::ffff:x.x.x.x): bytes[10]==0xFF, bytes[11]==0xFF.
                // JVM's isSiteLocalAddress()/isLinkLocalAddress() liefert false fuer diese Adressen
                // auf vielen Implementierungen — explizit pruefen.
                if (b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) {
                    // IPv4-Teil aus bytes[12..15] extrahieren und pruefen.
                    val ipv4 = java.net.Inet4Address.getByAddress(b.copyOfRange(12, 16))
                    if (ipv4.isLoopbackAddress || ipv4.isSiteLocalAddress || ipv4.isLinkLocalAddress || ipv4.isAnyLocalAddress) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
