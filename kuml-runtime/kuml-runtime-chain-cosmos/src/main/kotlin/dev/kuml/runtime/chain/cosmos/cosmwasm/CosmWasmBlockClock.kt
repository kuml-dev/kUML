package dev.kuml.runtime.chain.cosmos.cosmwasm

import dev.kuml.runtime.chain.BlockClock
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V3.0.21 — BlockClock-Implementierung für CosmWasm/Tendermint.
 *
 * Tendermint-Block-Timestamps sind **RFC-3339-Strings** (z.B. "2024-01-01T00:00:00.123456789Z"),
 * anders als EVM=Sekunden, Sui=Millisekunden, Aptos=Mikrosekunden. Werden via [Instant.parse]
 * gelesen — volle Nanosekunden-Präzision.
 *
 * [BlockClock.currentTime] / [BlockClock.currentBlock] sind synchron; intern wird der letzte
 * erfolgreich gelesene Wert gecacht und per [refresh] (suspending) aktualisiert.
 */
public class CosmWasmBlockClock(
    private val rpc: CosmWasmRpcClient,
) : BlockClock {
    private val cachedBlock = AtomicLong(0L)
    private val cachedTime = AtomicReference(Instant.EPOCH)

    /** Liest den neuesten Block (status/header) und cacht Höhe + Zeitstempel. Suspending. */
    public suspend fun refresh() {
        val header = rpc.getLatestBlockHeader()
        val heightStr =
            header["height"]?.jsonPrimitive?.content
                ?: throw CosmWasmChainAdapterException.MalformedResponse("block header missing 'height'")
        val height =
            heightStr.toLongOrNull()
                ?: throw CosmWasmChainAdapterException.MalformedResponse("block height is not a long: '$heightStr'")
        val timeStr =
            header["time"]?.jsonPrimitive?.content
                ?: throw CosmWasmChainAdapterException.MalformedResponse("block header missing 'time'")
        val instant =
            try {
                Instant.parse(timeStr)
            } catch (e: DateTimeParseException) {
                throw CosmWasmChainAdapterException.MalformedResponse("block time is not RFC-3339: '$timeStr'", e)
            }
        cachedBlock.set(height)
        cachedTime.set(instant)
    }

    /** Letzter via [refresh] gelesener Block-Zeitstempel. Initial: Instant.EPOCH. */
    override fun currentTime(): Instant = cachedTime.get()

    /** Letzte via [refresh] gelesene Block-Höhe. Initial: 0L. */
    override fun currentBlock(): Long = cachedBlock.get()
}
