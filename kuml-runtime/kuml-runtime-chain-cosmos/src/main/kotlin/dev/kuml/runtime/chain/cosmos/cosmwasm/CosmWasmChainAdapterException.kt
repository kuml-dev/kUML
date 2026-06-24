package dev.kuml.runtime.chain.cosmos.cosmwasm

/**
 * V3.0.21 — Basis-Fehlertyp aller CosmWasm-Adapter-Operationen. Sealed → exhaustives `when`.
 */
public sealed class CosmWasmChainAdapterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** JSON-RPC-Server lieferte ein `error`-Objekt (`code`, `message`). */
    public class RpcError(
        public val code: Int,
        public val rpcMessage: String,
        public val rpcData: String? = null,
    ) : CosmWasmChainAdapterException("JSON-RPC error $code: $rpcMessage")

    /** Transport-/IO-Fehler (DNS, Connection refused, Timeout, non-2xx HTTP). */
    public class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : CosmWasmChainAdapterException(message, cause)

    /** Antwort-JSON entsprach nicht dem erwarteten Schema (fehlende Felder etc.). */
    public class MalformedResponse(
        message: String,
        cause: Throwable? = null,
    ) : CosmWasmChainAdapterException(message, cause)

    /** URL verstößt gegen SSRF-Schutzregeln. */
    public class InvalidUrlException(
        message: String,
    ) : CosmWasmChainAdapterException(message)

    /** Ungültige Bech32-Contract-Adresse. */
    public class InvalidContractAddress(
        address: String,
    ) : CosmWasmChainAdapterException("Invalid CosmWasm contract address: '$address'")

    /**
     * Replay-Fenster überschreitet das konfigurierte Maximum.
     * Verhindert unbegrenzte RPC-Aufrufe und Speicherwachstum bei großen Replay-Fenstern.
     */
    public class ReplayWindowExceeded(
        fromBlock: Long,
        toBlock: Long,
        maxBlocks: Long,
    ) : CosmWasmChainAdapterException(
            "Replay window too large: $fromBlock..$toBlock = ${toBlock - fromBlock + 1} blocks, max is $maxBlocks. " +
                "Split into smaller windows or increase maxReplayBlocks.",
        )
}
