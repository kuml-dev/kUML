package dev.kuml.runtime.chain.cosmos.substrate

/**
 * V3.0.21 — Basis-Fehlertyp aller Substrate-Adapter-Operationen. Sealed → exhaustives `when`.
 */
public sealed class SubstrateChainAdapterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** JSON-RPC-Server lieferte ein `error`-Objekt (`code`, `message`). */
    public class RpcError(
        public val code: Int,
        public val rpcMessage: String,
        public val rpcData: String? = null,
    ) : SubstrateChainAdapterException("JSON-RPC error $code: $rpcMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout, non-2xx HTTP). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : SubstrateChainAdapterException(message, cause)

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : SubstrateChainAdapterException(message, cause)

    /** URL verstößt gegen SSRF-Schutzregeln. */
    public class InvalidUrlException(
        message: String,
    ) : SubstrateChainAdapterException(message)

    /** Ungültige SS58-Contract-Adresse. */
    public class InvalidContractAddress(
        address: String,
    ) : SubstrateChainAdapterException("Invalid Substrate contract address (SS58): '$address'")

    /**
     * Replay-Fenster überschreitet das konfigurierte Maximum.
     * Verhindert unbegrenzte RPC-Aufrufe und Speicherwachstum.
     */
    public class ReplayWindowExceeded(
        fromBlock: Long,
        toBlock: Long,
        maxBlocks: Long,
    ) : SubstrateChainAdapterException(
            "Replay window too large: $fromBlock..$toBlock = ${toBlock - fromBlock + 1} blocks, max is $maxBlocks. " +
                "Split into smaller windows or increase maxReplayBlocks.",
        )
}
