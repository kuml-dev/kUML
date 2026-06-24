package dev.kuml.runtime.chain.cosmos.substrate

import java.math.BigInteger
import java.security.MessageDigest

/**
 * V3.0.21 — SS58-Adress-Validierung für Substrate/Polkadot-Chains.
 *
 * SS58 = base58check über: <addressType-Byte(s)> ++ <32-Byte-AccountId> ++ <2-Byte-Checksumme>.
 * Die Checksumme ist Blake2b-512("SS58PRE" ++ prefix ++ payload)[0..2].
 *
 * Da Blake2b nicht in der JDK-Standard-MessageDigest-Registry liegt, prüft diese Klasse
 * defensiv das **Format** (base58-Alphabet, dekodierte Länge passend zu 1-Byte- oder
 * 2-Byte-Prefix + 32-Byte-AccountId + 2-Byte-Checksumme) und — sofern eine Blake2b-Impl
 * über [MessageDigest] verfügbar ist (z.B. via BouncyCastle im Classpath) — zusätzlich die
 * Checksumme. Ohne Blake2b-Provider bleibt es bei der strukturellen Prüfung (fail-open auf
 * Checksumme, fail-closed auf Format/Länge) — bewusst dokumentiert.
 */
public object SubstrateAddress {
    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE58_REV: IntArray =
        IntArray(128) { -1 }.also { rev ->
            BASE58_ALPHABET.forEachIndexed { i, c -> rev[c.code] = i }
        }
    private val SS58PRE = "SS58PRE".toByteArray(Charsets.US_ASCII)

    /** Strukturelle + (wenn möglich) Checksummen-Validierung einer SS58-Adresse. */
    public fun isValid(address: String): Boolean {
        if (address.isEmpty() || address.length > 64) return false
        if (address.any { it.code >= 128 || BASE58_REV[it.code] == -1 }) return false
        val decoded = base58Decode(address) ?: return false
        // 1-Byte-Prefix (simple): 1 + 32 + 2 = 35; 2-Byte-Prefix: 2 + 32 + 2 = 36.
        val prefixLen =
            when (decoded.size) {
                35 -> 1
                36 -> 2
                else -> return false
            }
        val checksum = blake2bOrNull() ?: return true // kein Blake2b-Provider → Format gilt
        val body = decoded.copyOfRange(0, decoded.size - 2)
        val expected = decoded.copyOfRange(decoded.size - 2, decoded.size)
        checksum.update(SS58PRE)
        checksum.update(body)
        val full = checksum.digest()
        return full[0] == expected[0] && full[1] == expected[1] && prefixLen > 0
    }

    /** Base58-Dekodierung. Gibt null bei ungültigem Zeichen zurück. */
    internal fun base58Decode(input: String): ByteArray? {
        var intData = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in input) {
            val digit = BASE58_REV.getOrElse(c.code) { -1 }
            if (digit < 0) return null
            intData = intData.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        var bytes = intData.toByteArray()
        // BigInteger kann ein führendes 0-Sign-Byte hinzufügen — entfernen.
        if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
        // Führende '1'-Zeichen → führende Nullbytes.
        var leadingZeros = 0
        for (c in input) {
            if (c == '1') leadingZeros++ else break
        }
        return ByteArray(leadingZeros) + bytes
    }

    /**
     * Liefert eine neue Blake2b-512-MessageDigest-Instanz oder null, wenn kein Provider
     * (z.B. BouncyCastle) im Classpath registriert ist. Neue Instanz pro Aufruf → thread-safe.
     */
    private fun blake2bOrNull(): MessageDigest? =
        try {
            MessageDigest.getInstance("BLAKE2B-512")
        } catch (_: Exception) {
            null
        }
}
