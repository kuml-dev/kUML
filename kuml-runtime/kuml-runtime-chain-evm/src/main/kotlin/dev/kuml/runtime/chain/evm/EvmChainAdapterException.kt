package dev.kuml.runtime.chain.evm

/**
 * Basis-Fehlertyp aller EVM-Adapter-Operationen. Sealed → exhaustives `when`
 * in Aufrufer-Code.
 */
public sealed class EvmChainAdapterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** JSON-RPC-Server lieferte ein `error`-Objekt (`code`, `message`). */
    public class RpcError(
        public val code: Int,
        public val rpcMessage: String,
        public val rpcData: String? = null,
    ) : EvmChainAdapterException("JSON-RPC error $code: $rpcMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout, non-2xx HTTP). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : EvmChainAdapterException(message, cause)

    /**
     * Eine zuvor gesehene Blockhöhe lieferte beim Re-Read einen anderen
     * Block-Hash → Chain-Reorg. Aufrufer soll ab [reorgFromBlock] neu replayen.
     */
    public class ReorgDetected(
        public val reorgFromBlock: Long,
        public val expectedBlockHash: String,
        public val actualBlockHash: String,
    ) : EvmChainAdapterException(
            "Reorg at block $reorgFromBlock: expected $expectedBlockHash but chain now reports $actualBlockHash",
        )

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : EvmChainAdapterException(message, cause)
}
