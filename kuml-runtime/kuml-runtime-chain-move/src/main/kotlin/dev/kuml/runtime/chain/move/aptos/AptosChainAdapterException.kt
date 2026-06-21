package dev.kuml.runtime.chain.move.aptos

/**
 * V3.0.20 — Basis-Fehlertyp aller Aptos-Adapter-Operationen. Sealed → exhaustives `when`.
 */
public sealed class AptosChainAdapterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Aptos REST API lieferte einen HTTP-Fehler-Status. */
    public class ApiError(
        public val httpStatus: Int,
        public val apiMessage: String,
    ) : AptosChainAdapterException("Aptos API error HTTP $httpStatus: $apiMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : AptosChainAdapterException(message, cause)

    /** Die contractAddress ist kein gültiges Aptos-Adressformat (0x + 1..64 hex chars). */
    public class InvalidAddressException(
        public val address: String,
    ) : AptosChainAdapterException("Invalid Aptos address: '$address' (expected 0x + 1..64 hex chars)")

    /** Die rpcUrl/baseUrl verstößt gegen SSRF-Schutzregeln (Schema, IP-Range). */
    public class InvalidUrlException(
        message: String,
        cause: Throwable? = null,
    ) : AptosChainAdapterException(message, cause)

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : AptosChainAdapterException(message, cause)
}
