package dev.kuml.runtime.chain.move.sui

import dev.kuml.runtime.chain.BlockClock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V3.0.20 — BlockClock-Implementierung für Sui, basierend auf Checkpoint-Sequenznummern.
 *
 * Sui-Timestamps sind in **Millisekunden** (anders als EVM=Sekunden, Aptos=Mikrosekunden).
 * [BlockClock.currentTime] / [BlockClock.currentBlock] sind synchron; intern wird der
 * letzte erfolgreich gelesene Wert gecacht und per [refresh] (suspending) aktualisiert.
 */
public class SuiBlockClock(
    private val rpc: SuiRpcClient,
) : BlockClock {
    private val cachedBlock = AtomicLong(0L)
    private val cachedTime = AtomicReference(Instant.EPOCH)

    /**
     * Liest die neueste Checkpoint-Sequenznummer und deren timestampMs.
     * Suspending.
     */
    public suspend fun refresh() {
        val checkpoint = rpc.getLatestCheckpointSequenceNumber()
        val cpJson = rpc.getCheckpoint(checkpoint).jsonObject
        val tsMs =
            cpJson["timestampMs"]?.jsonPrimitive?.content
                ?: throw SuiChainAdapterException.MalformedResponse("Checkpoint missing 'timestampMs'")
        val tsLong =
            tsMs.toLongOrNull()
                ?: throw SuiChainAdapterException.MalformedResponse("timestampMs is not a valid long: '$tsMs'")
        cachedBlock.set(checkpoint)
        // Sui-Timestamps sind Millisekunden — ofEpochMilli (NICHT ofEpochSecond wie EVM)
        cachedTime.set(Instant.ofEpochMilli(tsLong))
    }

    /** Letzter via [refresh] gelesener Checkpoint-Zeitstempel. Initial: Instant.EPOCH. */
    override fun currentTime(): Instant = cachedTime.get()

    /** Letzte via [refresh] gelesene Checkpoint-Sequenznummer. Initial: 0L. */
    override fun currentBlock(): Long = cachedBlock.get()
}
