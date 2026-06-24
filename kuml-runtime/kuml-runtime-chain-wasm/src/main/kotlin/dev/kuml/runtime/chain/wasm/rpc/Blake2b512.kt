package dev.kuml.runtime.chain.wasm.rpc

/**
 * Minimale, reine-Kotlin Blake2b-512-Implementierung ohne externe Abhaengigkeiten.
 *
 * Wird ausschliesslich fuer die SS58-Adress-Checksum-Verifizierung benoetigt
 * (`first 2 bytes of Blake2b-512("SS58PRE" + payload)`).
 *
 * Implementiert nach RFC 7693 / BLAKE2 Spec (https://www.blake2.net/blake2.pdf).
 * Unterstuetzt nur den keyed-less, output-length=64-Byte-Fall (Blake2b-512 ohne Key).
 *
 * Pure Kotlin, Native-Image-tauglich, kein java.security-Aufruf.
 */
@Suppress("MagicNumber")
internal object Blake2b512 {
    // Initialization vector (sqrt of first 8 primes, fractional parts, as per Blake2b/SHA-512 spec).
    // Kotlin Long is signed (64-bit two's complement). Values with the high bit set are negative;
    // we use java.lang.Long.parseUnsignedLong to correctly parse all 64-bit hex literals.
    private val IV: LongArray =
        longArrayOf(
            java.lang.Long.parseUnsignedLong("6a09e667f3bcc908", 16),
            java.lang.Long.parseUnsignedLong("bb67ae8584caa73b", 16),
            java.lang.Long.parseUnsignedLong("3c6ef372fe94f82b", 16),
            java.lang.Long.parseUnsignedLong("a54ff53a5f1d36f1", 16),
            java.lang.Long.parseUnsignedLong("510e527fade682d1", 16),
            java.lang.Long.parseUnsignedLong("9b05688c2b3e6c1f", 16),
            java.lang.Long.parseUnsignedLong("1f83d9abfb41bd6b", 16),
            java.lang.Long.parseUnsignedLong("5be0cd19137e2179", 16),
        )

    // Sigma permutation table (12 rounds x 16 indices)
    private val SIGMA: Array<IntArray> =
        arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        )

    /** Berechnet Blake2b-512-Hash (64 Bytes, kein Key) ueber [input]. */
    fun hash(input: ByteArray): ByteArray {
        // State: h[0..7] = IV XOR parameter block
        val h = LongArray(8) { IV[it] }
        // Parameter block p0: fanout=1, depth=1, output_length=64 in h[0] XOR
        // Fan-out=1 (byte 2), maxDepth=1 (byte 3), output len=64=0x40 (byte 0)
        h[0] = h[0] xor 0x01010040L

        val blockSize = 128
        val totalLen = input.size

        // Pad input to multiple of 128 bytes; at least one (possibly empty) final block.
        val numBlocks = maxOf(1, (totalLen + blockSize - 1) / blockSize)
        var bytesCompressed = 0L
        for (blockIdx in 0 until numBlocks) {
            val isLast = blockIdx == numBlocks - 1
            val offset = blockIdx * blockSize
            val blockData = ByteArray(blockSize) // zero-padded
            val toCopy = (totalLen - offset).coerceIn(0, blockSize)
            if (toCopy > 0) System.arraycopy(input, offset, blockData, 0, toCopy)

            bytesCompressed +=
                if (isLast) {
                    (totalLen - blockIdx * blockSize).toLong()
                } else {
                    blockSize.toLong()
                }

            compress(h, blockData, bytesCompressed, isLast)
        }

        // Serialize state as little-endian 64-byte output
        val out = ByteArray(64)
        for (i in 0 until 8) {
            val v = h[i]
            for (b in 0 until 8) out[i * 8 + b] = (v ushr (8 * b)).toByte()
        }
        return out
    }

    private fun compress(
        h: LongArray,
        block: ByteArray,
        counter: Long,
        isLast: Boolean,
    ) {
        // Build message schedule m[0..15] from block (little-endian u64)
        val m = LongArray(16)
        for (i in 0 until 16) {
            var v = 0L
            for (b in 0 until 8) v = v or ((block[i * 8 + b].toLong() and 0xFFL) shl (8 * b))
            m[i] = v
        }

        // Initialize working variables
        val v = LongArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[8 + i] = IV[i]
        v[12] = v[12] xor counter
        // v[13] xor counter-high = 0 (input always <= Long.MAX_VALUE bytes)
        if (isLast) v[14] = v[14].inv()

        // 12 rounds of G mixing
        for (round in 0 until 12) {
            val s = SIGMA[round]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        // Finalize
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(
        v: LongArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        x: Long,
        y: Long,
    ) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr64(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = rotr64(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = rotr64(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = rotr64(v[b] xor v[c], 63)
    }

    private fun rotr64(
        x: Long,
        n: Int,
    ): Long = (x ushr n) or (x shl (64 - n))
}
