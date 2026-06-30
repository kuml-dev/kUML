package dev.kuml.io.anim

import java.io.ByteArrayOutputStream

/**
 * Assembles an APNG (Animated PNG) byte stream from a list of PNG frames.
 *
 * Spec reference: https://wiki.mozilla.org/APNG_Spec
 *
 * Each element of [frames] must be a valid single-frame PNG byte array.
 * [delayMs] is the per-frame display duration in milliseconds (same for all frames).
 * [numPlays] is the loop count: 0 = loop forever.
 *
 * APNG chunk layout:
 * ```
 * PNG signature
 * IHDR          (from first frame)
 * acTL          (num_frames, num_plays)
 * [for each frame i]
 *   fcTL        (seq, width, height, x=0, y=0, delay_num, delay_den=1000, dispose=NONE, blend=SOURCE)
 *   IDAT / fdAT (frame i=0 uses IDAT, i>0 wraps IDAT payload in fdAT)
 * IEND          (single, at the very end)
 * ```
 *
 * Sequence numbers are shared between fcTL and fdAT and must be strictly monotonically
 * increasing starting from 0.
 */
public object ApngEncoder {
    private const val DISPOSE_OP_NONE: Byte = 0
    private const val BLEND_OP_SOURCE: Byte = 0

    /**
     * Encode [frames] as an APNG byte array.
     *
     * @param frames List of single-frame PNG byte arrays (length ≥ 1).
     * @param delayMs Display duration per frame in milliseconds.
     * @param numPlays Loop count (0 = loop forever).
     * @throws AnimEncoderException if a frame PNG cannot be parsed.
     */
    public fun encode(
        frames: List<ByteArray>,
        delayMs: Long,
        numPlays: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(delayMs > 0) { "delayMs must be > 0" }

        val out = ByteArrayOutputStream()
        var seqNo = 0

        // PNG signature
        out.write(PNG_SIGNATURE)

        // Parse first frame to get IHDR
        val firstChunks = parsePngChunks(frames[0])
        val ihdr =
            firstChunks.firstOrNull { it.typeString == "IHDR" }
                ?: throw AnimEncoderException("First frame does not contain an IHDR chunk")
        val width = ihdr.data.readInt(0)
        val height = ihdr.data.readInt(4)

        // IHDR from first frame
        out.write(ihdr.toBytes())

        // acTL — animation control chunk
        out.write(buildActl(frames.size, numPlays))

        for ((i, framePng) in frames.withIndex()) {
            val chunks = if (i == 0) firstChunks else parsePngChunks(framePng)

            // fcTL — frame control chunk (sequence number increments for each chunk)
            out.write(buildFctl(seqNo++, width, height, delayMs))

            // IDAT for frame 0, fdAT for frame 1+
            val idatChunks = chunks.filter { it.typeString == "IDAT" }
            if (idatChunks.isEmpty()) {
                throw AnimEncoderException("Frame $i has no IDAT chunk")
            }

            if (i == 0) {
                // First animation frame stays as IDAT
                for (idat in idatChunks) {
                    out.write(idat.toBytes())
                }
            } else {
                // Subsequent frames: wrap each IDAT payload in fdAT
                for (idat in idatChunks) {
                    out.write(buildFdat(seqNo++, idat.data))
                }
            }
        }

        // IEND
        out.write(buildIend())

        return out.toByteArray()
    }

    /** Build an acTL (animation control) chunk. */
    private fun buildActl(
        numFrames: Int,
        numPlays: Int,
    ): ByteArray {
        val data = ByteArrayOutputStream(8)
        data.writeInt(numFrames)
        data.writeInt(numPlays)
        return PngChunk(chunkType("acTL"), data.toByteArray()).toBytes()
    }

    /** Build an fcTL (frame control) chunk. delay_den=1000 so delay_num is milliseconds. */
    private fun buildFctl(
        seqNo: Int,
        width: Int,
        height: Int,
        delayMs: Long,
    ): ByteArray {
        val data = ByteArrayOutputStream(26)
        data.writeInt(seqNo)
        data.writeInt(width)
        data.writeInt(height)
        data.writeInt(0) // x_offset
        data.writeInt(0) // y_offset
        // delay_num and delay_den as 2-byte unsigned shorts
        val delayNum = delayMs.coerceAtMost(65535L).toInt()
        data.write((delayNum ushr 8) and 0xFF)
        data.write(delayNum and 0xFF)
        data.write(0x03) // delay_den high byte (1000 = 0x03E8)
        data.write(0xE8) // delay_den low byte
        data.write(DISPOSE_OP_NONE.toInt())
        data.write(BLEND_OP_SOURCE.toInt())
        return PngChunk(chunkType("fcTL"), data.toByteArray()).toBytes()
    }

    /** Build an fdAT (frame data) chunk wrapping raw IDAT payload bytes. */
    private fun buildFdat(
        seqNo: Int,
        idatPayload: ByteArray,
    ): ByteArray {
        val data = ByteArrayOutputStream(4 + idatPayload.size)
        data.writeInt(seqNo)
        data.write(idatPayload)
        return PngChunk(chunkType("fdAT"), data.toByteArray()).toBytes()
    }

    /** Build an IEND chunk. */
    private fun buildIend(): ByteArray = PngChunk(chunkType("IEND"), ByteArray(0)).toBytes()
}
