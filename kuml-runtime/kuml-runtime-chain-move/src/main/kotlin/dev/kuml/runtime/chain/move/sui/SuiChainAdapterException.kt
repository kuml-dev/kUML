package dev.kuml.runtime.chain.move.sui

/**
 * V3.0.20 — Basis-Fehlertyp aller Sui-Adapter-Operationen. Sealed → exhaustives `when`.
 */
public sealed class SuiChainAdapterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Sui JSON-RPC-Server lieferte ein `error`-Objekt (`code`, `message`). */
    public class RpcError(
        public val code: Int,
        public val rpcMessage: String,
        public val rpcData: String? = null,
    ) : SuiChainAdapterException("Sui JSON-RPC error $code: $rpcMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout, non-2xx HTTP). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : SuiChainAdapterException(message, cause)

    /** Die contractAddress ist kein gültiges Sui-Object-ID-Format (0x + 1..64 hex chars). */
    public class InvalidObjectId(
        public val objectId: String,
    ) : SuiChainAdapterException("Invalid Sui object id: '$objectId' (expected 0x + 1..64 hex chars)")

    /** Die rpcUrl verstößt gegen SSRF-Schutzregeln (Schema, IP-Range). */
    public class InvalidUrlException(
        message: String,
        cause: Throwable? = null,
    ) : SuiChainAdapterException(message, cause)

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : SuiChainAdapterException(message, cause)
}
