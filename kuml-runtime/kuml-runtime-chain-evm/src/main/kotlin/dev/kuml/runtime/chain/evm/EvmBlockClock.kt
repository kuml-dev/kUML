package dev.kuml.runtime.chain.evm

import dev.kuml.runtime.chain.BlockClock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * BlockClock-Implementierung, die den letzten **finalisierten** Block liest
 * (`eth_getBlockByNumber("finalized")`) und dessen `timestamp` als [Instant] liefert.
 *
 * [BlockClock.currentTime] / [BlockClock.currentBlock] sind synchron (Interface-Vorgabe);
 * intern wird der letzte erfolgreich gelesene Wert gecacht und per [refresh] (suspending)
 * aktualisiert. Wall-Clock-frei → deterministisch ledger-gebunden.
 *
 * @param finalityTag Ethereum Block-Tag. Erlaubt sind nur Werte aus [ALLOWED_FINALITY_TAGS];
 *   ein beliebiger String würde ungefiltert in den JSON-RPC-Params landen und zu
 *   MalformedResponse oder unerwartetem Verhalten führen.
 */
public class EvmBlockClock(
    private val rpc: EvmJsonRpcClient,
    private val finalityTag: String = "finalized",
) : BlockClock {
    init {
        require(finalityTag in ALLOWED_FINALITY_TAGS) {
            "finalityTag must be one of $ALLOWED_FINALITY_TAGS, got '$finalityTag'"
        }
    }

    private val cachedBlock = AtomicLong(0L)
    private val cachedTime = AtomicReference(Instant.EPOCH)
    private val cachedHash = AtomicReference<String?>(null)

    /**
     * Liest den finalisierten Block neu vom RPC und aktualisiert den Cache.
     * Suspending.
     */
    public suspend fun refresh() {
        val blockJson = rpc.ethGetBlockByTag(finalityTag, fullTx = false)
        val obj = blockJson.jsonObject

        val numberHex =
            obj["number"]?.jsonPrimitive?.content
                ?: throw EvmChainAdapterException.MalformedResponse("Block missing 'number' field")
        val timestampHex =
            obj["timestamp"]?.jsonPrimitive?.content
                ?: throw EvmChainAdapterException.MalformedResponse("Block missing 'timestamp' field")
        val hash = obj["hash"]?.jsonPrimitive?.content

        val blockNumber = EvmJsonRpcClient.parseHexQuantity(numberHex)
        val timestamp = EvmJsonRpcClient.parseHexQuantity(timestampHex)

        cachedBlock.set(blockNumber)
        cachedTime.set(Instant.ofEpochSecond(timestamp))
        cachedHash.set(hash)
    }

    /** Letzter via [refresh] gelesener Block-Zeitstempel. Initial: Instant.EPOCH bis erster refresh. */
    override fun currentTime(): Instant = cachedTime.get()

    /** Letzte via [refresh] gelesene finalisierte Block-Höhe. Initial: 0L. */
    override fun currentBlock(): Long = cachedBlock.get()

    /** Block-Hash des zuletzt gelesenen finalisierten Blocks (für Reorg-Vergleich). */
    public fun currentBlockHash(): String? = cachedHash.get()

    public companion object {
        /** Erlaubte Werte für den Ethereum Block-Tag-Parameter. */
        public val ALLOWED_FINALITY_TAGS: Set<String> =
            setOf("latest", "safe", "finalized", "earliest", "pending")
    }
}
