package dev.kuml.runtime.chain.move.sui

import java.net.InetAddress
import java.net.URI

/**
 * V3.0.20 — Strategie zur Validierung von Sui-RPC-URLs (SSRF-Schutz).
 *
 * Die Default-Implementierung [Default] führt die vollständige SSRF-Prüfung durch
 * (Schema-Whitelist http/https, IP-Range-Blocking für RFC 1918, Loopback, APIPA, Link-Local).
 * Für Unit-Tests mit einem MockRpcServer auf localhost kann [NoOp] injiziert werden.
 *
 * Hinweis: Nur IP-Literale werden auf private Ranges geprüft. Hostname-basierter
 * SSRF (DNS Rebinding) erfordert eine Allowlist auf Aufrufer-Seite — liegt außerhalb
 * des Schutzumfangs dieser Klasse.
 *
 * SSRF-Logik bewusst kopiert (nicht aus EVM-Modul referenziert) — geringere Kopplung
 * überwiegt DRY bei Security-Logik.
 */
public fun interface SuiRpcUrlValidator {
    /**
     * Validiert [rpcUrl]. Wirft [SuiChainAdapterException.InvalidUrlException] wenn die
     * URL gegen SSRF-Schutzregeln verstößt.
     */
    public fun validate(rpcUrl: String)

    public companion object {
        /** Produktion: vollständige SSRF-Validierung. */
        public val Default: SuiRpcUrlValidator = SuiRpcUrlValidator { url -> validateRpcUrl(url) }

        /**
         * Test-only: No-op-Validator, der alle URLs akzeptiert.
         * Erlaubt Verbindungen zu localhost/127.0.0.1 in Unit-Tests mit MockRpcServer.
         * Darf nur in Testcode instanziiert werden.
         */
        public val NoOp: SuiRpcUrlValidator = SuiRpcUrlValidator { /* alle URLs akzeptiert */ }

        private val ALLOWED_SCHEMES = setOf("http", "https")

        internal fun validateRpcUrl(rpcUrl: String) {
            val uri =
                try {
                    URI(rpcUrl)
                } catch (e: Exception) {
                    throw SuiChainAdapterException.InvalidUrlException(
                        "rpcUrl is not a valid URI: '$rpcUrl'",
                        e,
                    )
                }
            if (uri.scheme !in ALLOWED_SCHEMES) {
                throw SuiChainAdapterException.InvalidUrlException(
                    "rpcUrl must use http or https, got '${uri.scheme}'",
                )
            }
            val host =
                uri.host
                    ?: throw SuiChainAdapterException.InvalidUrlException(
                        "rpcUrl has no host: '$rpcUrl'",
                    )
            if (looksLikeIpLiteral(host)) {
                val addr =
                    try {
                        InetAddress.getByName(host)
                    } catch (e: Exception) {
                        throw SuiChainAdapterException.InvalidUrlException(
                            "rpcUrl host is not resolvable as IP: '$host'",
                            e,
                        )
                    }
                if (isPrivateOrLoopback(addr)) {
                    throw SuiChainAdapterException.InvalidUrlException(
                        "rpcUrl must not address private/loopback/link-local IP ranges (SSRF protection)",
                    )
                }
            }
        }

        /** Heuristik: sieht der Host-String wie ein IPv4/IPv6-Literal aus? */
        private fun looksLikeIpLiteral(host: String): Boolean {
            if (host.startsWith("[") || host.contains(":")) return true
            return host.all { it.isDigit() || it == '.' }
        }

        /** Prüft ob eine IP-Adresse zu einer privaten, loopback oder link-local Range gehört. */
        private fun isPrivateOrLoopback(addr: InetAddress): Boolean =
            addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress
    }
}
