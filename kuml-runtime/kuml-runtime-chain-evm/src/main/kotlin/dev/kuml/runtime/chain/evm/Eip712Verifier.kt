package dev.kuml.runtime.chain.evm

import java.math.BigInteger

/**
 * EIP-712 typed-data Signatur-Verifikation: rekonstruiert die signierende
 * Ethereum-Adresse aus (typedDataDigest, signature) via secp256k1-ecrecover
 * und vergleicht sie mit der erwarteten Adresse.
 *
 * Pure-Kotlin (kein Bouncy Castle): keccak256 + secp256k1-Punktarithmetik selbst
 * implementiert. JVM-only — nutzt java.math.BigInteger.
 *
 * EIP-712 Digest: keccak256( 0x1901 ‖ domainSeparator ‖ hashStruct(message) ).
 */
public class Eip712Verifier {
    /**
     * Verifiziert eine 65-Byte-Signatur (r‖s‖v) gegen den fertigen EIP-712-Digest.
     *
     * @param digest 32-Byte keccak256-Digest (0x1901-Präfix bereits eingerechnet).
     * @param signature 65 Bytes: r(32) ‖ s(32) ‖ v(1, 27/28 oder 0/1).
     * @param expectedAddress 20-Byte Ethereum-Adresse (case-insensitiv, mit/ohne 0x).
     * @return true gdw. die recoverte Adresse == expectedAddress.
     */
    public fun verify(
        digest: ByteArray,
        signature: ByteArray,
        expectedAddress: String,
    ): Boolean {
        val recovered = recoverAddress(digest, signature) ?: return false
        val expected = expectedAddress.removePrefix("0x").removePrefix("0X").lowercase()
        return recovered.removePrefix("0x") == expected
    }

    /** Wie [verify], aber baut den Digest aus domainSeparator + structHash (0x1901-Präfix). */
    public fun verifyTypedData(
        domainSeparator: ByteArray,
        structHash: ByteArray,
        signature: ByteArray,
        expectedAddress: String,
    ): Boolean {
        val digest = eip712Digest(domainSeparator, structHash)
        return verify(digest, signature, expectedAddress)
    }

    /**
     * Recovert die 20-Byte-Adresse (als 0x-lowercase-Hex) aus Digest + Signatur,
     * oder null wenn die Recovery fehlschlägt (ungültiges v, Punkt nicht auf Kurve).
     */
    public fun recoverAddress(
        digest: ByteArray,
        signature: ByteArray,
    ): String? {
        if (signature.size != 65) return null
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        val vByte = signature[64].toInt() and 0xFF
        val v =
            when (vByte) {
                27, 0 -> 0
                28, 1 -> 1
                else -> return null
            }
        val pubKey = Secp256k1.ecRecover(digest, r, s, v) ?: return null
        return "0x" + addressFromPublicKey(pubKey)
    }

    public companion object {
        /**
         * keccak256 (Ethereum-Variante, NICHT SHA3-256).
         * Ethereum nutzt Keccak-f[1600] mit `0x01`-Padding, NIST SHA3 nutzt `0x06`.
         * Pure-Kotlin. → 32 Byte.
         */
        public fun keccak256(input: ByteArray): ByteArray = Keccak256.hash(input)

        /** EIP-712 Digest: keccak256(0x1901 ‖ domainSeparator ‖ structHash). */
        public fun eip712Digest(
            domainSeparator: ByteArray,
            structHash: ByteArray,
        ): ByteArray {
            val prefix = byteArrayOf(0x19.toByte(), 0x01.toByte())
            return keccak256(prefix + domainSeparator + structHash)
        }

        /**
         * Adresse aus uncompressed Pubkey (64 Byte, ohne 0x04-Präfix):
         * keccak256(pubkey).takeLast(20).
         */
        public fun addressFromPublicKey(publicKey: ByteArray): String {
            require(publicKey.size == 64) { "Public key must be 64 bytes (uncompressed, no 0x04 prefix)" }
            val hash = keccak256(publicKey)
            return hash.takeLast(20).joinToString("") { "%02x".format(it) }
        }
    }
}

// ── Internal Keccak-256 implementation ──────────────────────────────────────────
// Ethereum variant: uses 0x01 domain suffix (not the NIST SHA3 0x06 suffix).

internal object Keccak256 {
    private const val RATE_BYTES = 136 // 1600 - 512 = 1088 bits = 136 bytes
    private const val ROUNDS = 24

    // Round constants — parsed via parseUnsignedLong to handle values with high bit set
    @Suppress("MagicNumber")
    private val RC =
        longArrayOf(
            java.lang.Long.parseUnsignedLong("0000000000000001", 16),
            java.lang.Long.parseUnsignedLong("0000000000008082", 16),
            java.lang.Long.parseUnsignedLong("800000000000808A", 16),
            java.lang.Long.parseUnsignedLong("8000000080008000", 16),
            java.lang.Long.parseUnsignedLong("000000000000808B", 16),
            java.lang.Long.parseUnsignedLong("0000000080000001", 16),
            java.lang.Long.parseUnsignedLong("8000000080008081", 16),
            java.lang.Long.parseUnsignedLong("8000000000008009", 16),
            java.lang.Long.parseUnsignedLong("000000000000008A", 16),
            java.lang.Long.parseUnsignedLong("0000000000000088", 16),
            java.lang.Long.parseUnsignedLong("0000000080008009", 16),
            java.lang.Long.parseUnsignedLong("000000008000000A", 16),
            java.lang.Long.parseUnsignedLong("000000008000808B", 16),
            java.lang.Long.parseUnsignedLong("800000000000008B", 16),
            java.lang.Long.parseUnsignedLong("8000000000008089", 16),
            java.lang.Long.parseUnsignedLong("8000000000008003", 16),
            java.lang.Long.parseUnsignedLong("8000000000008002", 16),
            java.lang.Long.parseUnsignedLong("8000000000000080", 16),
            java.lang.Long.parseUnsignedLong("000000000000800A", 16),
            java.lang.Long.parseUnsignedLong("800000008000000A", 16),
            java.lang.Long.parseUnsignedLong("8000000080008081", 16),
            java.lang.Long.parseUnsignedLong("8000000000008080", 16),
            java.lang.Long.parseUnsignedLong("0000000080000001", 16),
            java.lang.Long.parseUnsignedLong("8000000080008008", 16),
        )

    // Rotation offsets
    private val ROT =
        intArrayOf(
            1,
            3,
            6,
            10,
            15,
            21,
            28,
            36,
            45,
            55,
            2,
            14,
            27,
            41,
            56,
            8,
            25,
            43,
            62,
            18,
            39,
            61,
            20,
            44,
        )

    // Pi permutation indices
    private val PI =
        intArrayOf(
            10,
            7,
            11,
            17,
            18,
            3,
            5,
            16,
            8,
            21,
            24,
            4,
            15,
            23,
            19,
            13,
            12,
            2,
            20,
            14,
            22,
            9,
            6,
            1,
        )

    fun hash(input: ByteArray): ByteArray {
        // Pad the input: Ethereum keccak uses 0x01 domain byte (not 0x06 for NIST SHA3)
        val msgLen = input.size
        val padded = ByteArray(msgLen + RATE_BYTES - (msgLen % RATE_BYTES))
        input.copyInto(padded)
        padded[msgLen] = 0x01.toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()

        // Initialize state (25 lanes × 64 bits)
        val state = LongArray(25)

        // Absorb
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until RATE_BYTES / 8) {
                var lane = 0L
                for (j in 0 until 8) {
                    lane = lane or ((padded[offset + i * 8 + j].toLong() and 0xFFL) shl (j * 8))
                }
                state[i] = state[i] xor lane
            }
            keccakF(state)
            offset += RATE_BYTES
        }

        // Squeeze: first 32 bytes = 4 lanes
        val output = ByteArray(32)
        for (i in 0 until 4) {
            val lane = state[i]
            for (j in 0 until 8) {
                output[i * 8 + j] = ((lane shr (j * 8)) and 0xFF).toByte()
            }
        }
        return output
    }

    private fun keccakF(state: LongArray) {
        val bc = LongArray(5)

        repeat(ROUNDS) { round ->
            // Theta
            for (i in 0 until 5) {
                bc[i] = state[i] xor state[i + 5] xor state[i + 10] xor state[i + 15] xor state[i + 20]
            }
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor rotL(bc[(i + 1) % 5], 1)
                for (j in 0 until 5) {
                    state[i + j * 5] = state[i + j * 5] xor t
                }
            }

            // Rho and Pi
            var last = state[1]
            for (i in 0 until 24) {
                val j = PI[i]
                val cur = state[j]
                state[j] = rotL(last, ROT[i])
                last = cur
            }

            // Chi
            for (j in 0 until 5) {
                for (i in 0 until 5) {
                    bc[i] = state[i + j * 5]
                }
                for (i in 0 until 5) {
                    state[i + j * 5] = bc[i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                }
            }

            // Iota
            state[0] = state[0] xor RC[round]
        }
    }

    private fun rotL(
        v: Long,
        n: Int,
    ): Long = (v shl n) or (v ushr (64 - n))
}

// ── Internal secp256k1 implementation ───────────────────────────────────────────

internal object Secp256k1 {
    // secp256k1 curve parameters
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val B = BigInteger("7") // a=0, b=7
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.TWO
    private val THREE = BigInteger("3")

    data class Point(
        val x: BigInteger,
        val y: BigInteger,
    ) {
        companion object {
            val INFINITY = Point(BigInteger.ZERO, BigInteger.ZERO)
        }

        val isInfinity: Boolean get() = x == BigInteger.ZERO && y == BigInteger.ZERO
    }

    private fun pointAdd(
        p: Point,
        q: Point,
    ): Point {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        if (p.x == q.x) {
            // p.y == q.y → point doubling; p.y != q.y (i.e., q = -p) → infinity
            if (p.y != q.y) return Point.INFINITY
            if (p.y == BigInteger.ZERO) return Point.INFINITY
            // Doubling: λ = (3x² + a) / (2y) mod P, a=0 for secp256k1
            val num = THREE.multiply(p.x.multiply(p.x).mod(P)).mod(P)
            val den = TWO.multiply(p.y).mod(P)
            val lam = num.multiply(den.modInverse(P)).mod(P)
            val rx = lam.multiply(lam).subtract(TWO.multiply(p.x)).mod(P)
            val ry = lam.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
            return Point(rx, ry)
        }
        // Addition: λ = (y2 - y1) / (x2 - x1) mod P
        val num = q.y.subtract(p.y).mod(P)
        val den = q.x.subtract(p.x).mod(P)
        val lam = num.multiply(den.modInverse(P)).mod(P)
        val rx =
            lam
                .multiply(lam)
                .subtract(p.x)
                .subtract(q.x)
                .mod(P)
        val ry = lam.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
        return Point(rx, ry)
    }

    private fun scalarMul(
        k: BigInteger,
        pt: Point,
    ): Point {
        var result = Point.INFINITY
        var addend = pt
        var scalar = k.mod(N)
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    private fun yFromX(
        x: BigInteger,
        yParity: Int,
    ): BigInteger? {
        // y^2 = x^3 + 7 (mod P)
        val y2 = x.modPow(THREE, P).add(B).mod(P)
        // sqrt via Tonelli: p ≡ 3 (mod 4) → y = y2^((p+1)/4)
        val exp = P.add(BigInteger.ONE).divide(BigInteger("4"))
        val y = y2.modPow(exp, P)
        if (y.multiply(y).mod(P) != y2) return null // not on curve
        // Adjust parity
        return if (y.testBit(0) == (yParity == 1)) y else P.subtract(y)
    }

    // Half of the curve order N, used for EIP-2 Low-S enforcement.
    // EIP-2 (Ethereum Homestead) requires s <= HALF_N to prevent signature malleability:
    // for any valid (r, s), the pair (r, N-s) would also verify to the same address,
    // which breaks replay-protection mechanisms that rely on signature uniqueness.
    private val HALF_N = N.shiftRight(1)

    /**
     * Recovers the 64-byte uncompressed public key from (digest, r, s, recoveryId).
     * Returns null if recovery fails.
     *
     * Guards against:
     * - Null/zero signature: r <= 0 catches all-zero r (and by extension the all-zero signature).
     * - Out-of-range values: r >= N, s >= N rejected explicitly.
     * - Signature malleability (EIP-2): s > HALF_N rejected (Low-S enforcement).
     *   For any valid (r, s), the pair (r, N-s) would recover the same address —
     *   accepting both enables replay attacks that bypass signature-hash-based protection.
     */
    fun ecRecover(
        digest: ByteArray,
        r: BigInteger,
        s: BigInteger,
        recId: Int,
    ): ByteArray? {
        if (r <= BigInteger.ZERO || r >= N) return null
        if (s <= BigInteger.ZERO || s >= N) return null
        // EIP-2 Low-S enforcement: reject high-S signatures to prevent malleability.
        if (s > HALF_N) return null

        // R = point with x = r (+ recId/2 * N if > P, but for typical Ethereum sigs recId < 2)
        val x = r.add(BigInteger.valueOf((recId / 2).toLong()).multiply(N))
        if (x >= P) return null

        val y = yFromX(x, recId and 1) ?: return null
        val rPoint = Point(x, y)

        val e = BigInteger(1, digest)
        val rInv = r.modInverse(N)

        // Q = r^-1 * (s*R - e*G)
        val sR = scalarMul(s, rPoint)
        val eG = scalarMul(e, Point(Gx, Gy))
        // eG negated: (eGx, P - eGy)
        val negEG = if (eG.isInfinity) Point.INFINITY else Point(eG.x, P.subtract(eG.y))
        val q = scalarMul(rInv, pointAdd(sR, negEG))

        if (q.isInfinity) return null

        // Return uncompressed public key without 0x04 prefix (64 bytes, big-endian left-padded)
        val xBytes = bigIntTo32Bytes(q.x)
        val yBytes = bigIntTo32Bytes(q.y)
        return xBytes + yBytes
    }

    /**
     * Converts a non-negative BigInteger to a big-endian 32-byte array.
     * Left-pads with zeros if shorter; truncates to last 32 bytes if longer
     * (BigInteger.toByteArray may include a leading 0x00 sign byte).
     */
    private fun bigIntTo32Bytes(n: BigInteger): ByteArray {
        val ba = n.toByteArray()
        return when {
            ba.size == 32 -> ba
            ba.size > 32 -> ba.copyOfRange(ba.size - 32, ba.size) // strip sign byte
            else -> {
                val padded = ByteArray(32)
                ba.copyInto(padded, destinationOffset = 32 - ba.size)
                padded
            }
        }
    }
}
