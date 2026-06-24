package dev.kuml.runtime.chain.cosmos.substrate

import dev.kuml.runtime.chain.BlockClock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V3.0.21 — BlockClock-Implementierung für Substrate.
 *
 * Substrate hat keine native Block-Zeit im Header; die Zeit liegt im `Timestamp.set`-Inherent
 * der jeweiligen Extrinsics-Liste und wird über `state_getStorage(Timestamp.Now)` gelesen.
 * Der Wert ist in **Millisekunden** (u64, SCALE-LE) — analog Sui-Konvertierung via ofEpochMilli.
 *
 * [BlockClock.currentTime] / [BlockClock.currentBlock] sind synchron; intern wird der letzte
 * erfolgreich gelesene Wert gecacht und per [refresh] (suspending) aktualisiert.
 */
public class SubstrateBlockClock(
    private val rpc: SubstrateRpcClient,
) : BlockClock {
    private val cachedBlock = AtomicLong(0L)
    private val cachedTime = AtomicReference(Instant.EPOCH)

    /** Liest finalisierte Block-Höhe + Timestamp.Now (Millis, SCALE-LE u64). Suspending. */
    public suspend fun refresh() {
        val height = rpc.getFinalizedHeight()
        cachedBlock.set(height)

        // Timestamp.Now Storage-Key liefert hex-encoded u64-LE (Millis). 0x → leer → EPOCH.
        val tsHex = rpc.getTimestampNowHex()
        val millis = decodeU64LittleEndian(tsHex)
        cachedTime.set(Instant.ofEpochMilli(millis))
    }

    /** Letzter via [refresh] gelesener Block-Zeitstempel. Initial: Instant.EPOCH. */
    override fun currentTime(): Instant = cachedTime.get()

    /** Letzte via [refresh] gelesene finalisierte Block-Höhe. Initial: 0L. */
    override fun currentBlock(): Long = cachedBlock.get()

    public companion object {
        /**
         * Dekodiert einen 0x-hex-encoded u64 in Little-Endian (SCALE-Konvention) zu Long (Millis).
         * Leerer/0x-leerer Wert → 0. Toleriert < 8 Bytes (rechts mit 0 aufgefüllt).
         */
        internal fun decodeU64LittleEndian(hex: String): Long {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            if (clean.isEmpty()) return 0L
            if (clean.length % 2 != 0) {
                throw SubstrateChainAdapterException.MalformedResponse("Timestamp hex has odd length: '$hex'")
            }
            val bytes =
                try {
                    ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                } catch (e: NumberFormatException) {
                    throw SubstrateChainAdapterException.MalformedResponse("Invalid timestamp hex: '$hex'", e)
                }
            var result = 0L
            for (i in 0 until minOf(8, bytes.size)) {
                result = result or ((bytes[i].toLong() and 0xFF) shl (8 * i))
            }
            return result
        }
    }
}
