package dev.kuml.runtime.chain.move.aptos

import java.net.InetAddress
import java.net.URI

/**
 * V3.0.20 — Strategie zur Validierung von Aptos-REST-Base-URLs (SSRF-Schutz).
 *
 * Die Default-Implementierung [Default] führt die vollständige SSRF-Prüfung durch
 * (Schema-Whitelist http/https, IP-Range-Blocking für RFC 1918, Loopback, APIPA, Link-Local).
 * Für Unit-Tests mit einem MockRestServer auf localhost kann [NoOp] injiziert werden.
 *
 * Hinweis: Nur IP-Literale werden auf private Ranges geprüft. Hostname-basierter
 * SSRF (DNS Rebinding) erfordert eine Allowlist auf Aufrufer-Seite — liegt außerhalb
 * des Schutzumfangs dieser Klasse.
 *
 * SSRF-Logik bewusst kopiert (nicht aus EVM- oder Sui-Modul referenziert) — geringere
 * Kopplung überwiegt DRY bei Security-Logik.
 */
public fun interface AptosUrlValidator {
    /**
     * Validiert [baseUrl]. Wirft [AptosChainAdapterException.InvalidUrlException] wenn die
     * URL gegen SSRF-Schutzregeln verstößt.
     */
    public fun validate(baseUrl: String)

    public companion object {
        /** Produktion: vollständige SSRF-Validierung. */
        public val Default: AptosUrlValidator = AptosUrlValidator { url -> validateBaseUrl(url) }

        /**
         * Test-only: No-op-Validator, der alle URLs akzeptiert.
         * Erlaubt Verbindungen zu localhost/127.0.0.1 in Unit-Tests mit MockRestServer.
         * Darf nur in Testcode instanziiert werden.
         */
        public val NoOp: AptosUrlValidator = AptosUrlValidator { /* alle URLs akzeptiert */ }

        private val ALLOWED_SCHEMES = setOf("http", "https")

        internal fun validateBaseUrl(baseUrl: String) {
            val uri =
                try {
                    URI(baseUrl)
                } catch (e: Exception) {
                    throw AptosChainAdapterException.InvalidUrlException(
                        "baseUrl is not a valid URI: '$baseUrl'",
                        e,
                    )
                }
            if (uri.scheme !in ALLOWED_SCHEMES) {
                throw AptosChainAdapterException.InvalidUrlException(
                    "baseUrl must use http or https, got '${uri.scheme}'",
                )
            }
            val host =
                uri.host
                    ?: throw AptosChainAdapterException.InvalidUrlException(
                        "baseUrl has no host: '$baseUrl'",
                    )
            if (looksLikeIpLiteral(host)) {
                val addr =
                    try {
                        InetAddress.getByName(host)
                    } catch (e: Exception) {
                        throw AptosChainAdapterException.InvalidUrlException(
                            "baseUrl host is not resolvable as IP: '$host'",
                            e,
                        )
                    }
                if (isPrivateOrLoopback(addr)) {
                    throw AptosChainAdapterException.InvalidUrlException(
                        "baseUrl must not address private/loopback/link-local IP ranges (SSRF protection)",
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
