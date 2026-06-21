package dev.kuml.runtime.chain.move.aptos

import dev.kuml.runtime.chain.BlockClock
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V3.0.20 — BlockClock-Implementierung für Aptos, basierend auf der Ledger-Version.
 *
 * Aptos-Timestamps sind in **Mikrosekunden** (anders als EVM=Sekunden, Sui=Millisekunden).
 * Konvertierung: Instant.ofEpochSecond(µs / 1_000_000, (µs % 1_000_000) * 1_000)
 * für volle Mikro-Präzision.
 *
 * [BlockClock.currentTime] / [BlockClock.currentBlock] sind synchron; intern wird der
 * letzte erfolgreich gelesene Wert gecacht und per [refresh] (suspending) aktualisiert.
 */
public class AptosBlockClock(
    private val rest: AptosRestClient,
) : BlockClock {
    private val cachedBlock = AtomicLong(0L)
    private val cachedTime = AtomicReference(Instant.EPOCH)

    /**
     * Liest die aktuelle Ledger-Version + Block-Timestamp vom Fullnode.
     * Suspending.
     */
    public suspend fun refresh() {
        val ledger = rest.getLedgerInfo()
        val version =
            ledger["ledger_version"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw AptosChainAdapterException.MalformedResponse("ledger info missing 'ledger_version'")
        cachedBlock.set(version)

        val height =
            ledger["block_height"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val block = rest.getBlockByHeight(height)
        val tsMicros =
            block["block_timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw AptosChainAdapterException.MalformedResponse("block missing 'block_timestamp'")
        // Aptos-Timestamps sind Mikrosekunden — volle Mikro-Präzision via ofEpochSecond
        cachedTime.set(
            Instant.ofEpochSecond(tsMicros / 1_000_000L, (tsMicros % 1_000_000L) * 1_000L),
        )
    }

    /** Letzter via [refresh] gelesener Block-Zeitstempel. Initial: Instant.EPOCH. */
    override fun currentTime(): Instant = cachedTime.get()

    /** Letzte via [refresh] gelesene Ledger-Version. Initial: 0L. */
    override fun currentBlock(): Long = cachedBlock.get()
}
