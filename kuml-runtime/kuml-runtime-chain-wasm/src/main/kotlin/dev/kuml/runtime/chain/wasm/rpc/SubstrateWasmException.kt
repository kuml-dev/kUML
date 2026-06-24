package dev.kuml.runtime.chain.wasm.rpc

/**
 * V3.0.22 — Basis-Fehlertyp aller WASM-Adapter-Operationen. Sealed → exhaustives `when`.
 */
public sealed class SubstrateWasmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** JSON-RPC-Server lieferte ein `error`-Objekt (`code`, `message`). */
    public class RpcError(
        public val code: Int,
        public val rpcMessage: String,
        public val rpcData: String? = null,
    ) : SubstrateWasmException("JSON-RPC error $code: $rpcMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout, non-2xx HTTP). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : SubstrateWasmException(message, cause)

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : SubstrateWasmException(message, cause)

    /** URL verstoeßt gegen SSRF-Schutzregeln. */
    public class InvalidUrl(
        message: String,
    ) : SubstrateWasmException(message)

    /** Ungueltige Contract-Adresse (leer, zu kurz, falsches Format). */
    public class InvalidAddress(
        address: String,
    ) : SubstrateWasmException("Invalid WASM contract address: '$address'")
}
