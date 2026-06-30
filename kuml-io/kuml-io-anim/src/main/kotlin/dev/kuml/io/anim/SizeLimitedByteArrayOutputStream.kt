package dev.kuml.io.anim

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Thrown by [SizeLimitedByteArrayOutputStream] when the written data exceeds
 * the configured byte limit.
 */
internal class SizeLimitExceededException(
    limitBytes: Long,
) : RuntimeException("Output exceeds the $limitBytes byte per-frame limit")

/**
 * A [ByteArrayOutputStream] that throws [SizeLimitExceededException] as soon as
 * more than [limitBytes] bytes have been written to it.
 *
 * Used by [BatikFrameSampler] to prevent a single large animation frame from
 * exhausting heap before the total-output-size guard in [KumlAnimRenderer] fires.
 *
 * @param limitBytes Maximum number of bytes that may be written before an exception
 *   is thrown.  Must be > 0.
 */
internal class SizeLimitedByteArrayOutputStream(
    private val limitBytes: Long,
) : OutputStream() {
    init {
        require(limitBytes > 0) { "limitBytes must be > 0, got $limitBytes" }
    }

    private val backing = ByteArrayOutputStream()
    private var written = 0L

    override fun write(b: Int) {
        checkLimit(1)
        backing.write(b)
        written++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        checkLimit(len.toLong())
        backing.write(b, off, len)
        written += len
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun flush() = backing.flush()

    override fun close() = backing.close()

    fun toByteArray(): ByteArray = backing.toByteArray()

    private fun checkLimit(additionalBytes: Long) {
        if (written + additionalBytes > limitBytes) {
            throw SizeLimitExceededException(limitBytes)
        }
    }
}
