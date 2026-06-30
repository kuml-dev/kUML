package dev.kuml.io.anim

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** PNG file signature (8 bytes). */
internal val PNG_SIGNATURE =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

/**
 * Minimal PNG chunk reader and writer used by the APNG assembler.
 *
 * A PNG file is a sequence of chunks: `length (4 bytes) + type (4 bytes) + data + crc32 (4 bytes)`.
 * The CRC covers type + data.
 *
 * This class is intentionally minimal — it only supports what [ApngEncoder] needs.
 */
internal data class PngChunk(
    val type: ByteArray,
    val data: ByteArray,
) {
    val typeString: String get() = String(type, Charsets.ISO_8859_1)

    /** Serialise this chunk to bytes including length, type, data, and CRC32. */
    fun toBytes(): ByteArray {
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        val crcValue = crc.value.toInt()

        val out = ByteArrayOutputStream(4 + 4 + data.size + 4)
        out.writeInt(data.size)
        out.write(type)
        out.write(data)
        out.writeInt(crcValue)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PngChunk) return false
        return type.contentEquals(other.type) && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * type.contentHashCode() + data.contentHashCode()
}

/** Write a 4-byte big-endian int to a [ByteArrayOutputStream]. */
internal fun ByteArrayOutputStream.writeInt(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

/** Write a 4-byte big-endian unsigned int (supplied as Long) to a [ByteArrayOutputStream]. */
internal fun ByteArrayOutputStream.writeUInt(value: Long) = writeInt(value.toInt())

/** Read a 4-byte big-endian int from a [ByteArray] at [offset]. */
internal fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/**
 * Parse all chunks from a PNG byte array (skipping the 8-byte signature).
 *
 * Returns a list of [PngChunk] objects in file order.
 */
internal fun parsePngChunks(png: ByteArray): List<PngChunk> {
    val chunks = mutableListOf<PngChunk>()
    var pos = 8 // skip signature
    // A chunk is at minimum 12 bytes (4 length + 4 type + 0 data + 4 CRC), so there
    // must be at least 12 bytes remaining to attempt a read.  The old guard
    // `pos < png.size - 8` would stop 4 bytes too early and silently skip IEND
    // (length=0, type=IEND, data=<empty>, CRC = 12 bytes total) when it sits at
    // exactly `png.size - 12`.
    while (pos <= png.size - 12) {
        val length = png.readInt(pos)
        if (length < 0 || pos + 12 + length > png.size) break
        val type = png.copyOfRange(pos + 4, pos + 8)
        val data = if (length > 0) png.copyOfRange(pos + 8, pos + 8 + length) else ByteArray(0)
        chunks.add(PngChunk(type, data))
        pos += 12 + length
    }
    return chunks
}

/** Build chunk type bytes from a 4-character ASCII string. */
internal fun chunkType(name: String): ByteArray = name.toByteArray(Charsets.ISO_8859_1)
