package dev.kuml.runtime.chain.cosmos.cosmwasm

/**
 * V3.0.21 — Bech32-Adress-Validierung für Cosmos-SDK-Chains.
 *
 * Bech32-Format: "<hrp>1<data><checksum>" wobei
 * - hrp (human-readable part) = lowercase Präfix (z.B. "cosmos", "juno", "osmo", "neutron").
 * - separator = '1'.
 * - data + checksum = bech32-Alphabet "qpzry9x8gf2tvdw0s3jn54khce6mua7l" (kein b,i,o,1).
 *
 * CosmWasm-Contract-Adressen sind 32-Byte-Hashes → 52 bech32-data+checksum-Zeichen
 * (Account-Adressen sind 20-Byte → 38 Zeichen). [isValidContract] prüft zusätzlich
 * die contract-typische Länge, [isValid] nur das allgemeine Bech32-Format inkl. Checksumme.
 *
 * Implementiert die vollständige BIP-173-Checksummen-Prüfung (Polynom-Mod), damit Tippfehler
 * in Adressen erkannt werden — KEINE reine Regex-Heuristik.
 */
public object CosmosAddress {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV: IntArray =
        IntArray(128) { -1 }.also { rev ->
            CHARSET.forEachIndexed { i, c -> rev[c.code] = i }
        }

    /** Allgemeine Bech32-Validierung inkl. hrp, Separator, Alphabet, Längen und Checksumme. */
    public fun isValid(address: String): Boolean {
        if (address.length < 8 || address.length > 90) return false
        // Mixed case ist verboten.
        if (address != address.lowercase() && address != address.uppercase()) return false
        val addr = address.lowercase()
        val sep = addr.lastIndexOf('1')
        if (sep < 1) return false // hrp braucht mindestens 1 Zeichen
        val hrp = addr.substring(0, sep)
        val data = addr.substring(sep + 1)
        if (data.length < 6) return false // mindestens Checksumme (6 Zeichen)
        if (hrp.any { it.code < 33 || it.code > 126 }) return false
        val values = IntArray(data.length)
        for (i in data.indices) {
            val v = CHARSET_REV.getOrElse(data[i].code) { -1 }
            if (v == -1) return false
            values[i] = v
        }
        return verifyChecksum(hrp, values)
    }

    /**
     * Wie [isValid], zusätzlich Längen-Check für CosmWasm-Contract-Adressen.
     *
     * Akzeptiert:
     * - dataLen == 58: 32-Byte-Hash → 52 bech32-Datenzeichen + 6 Checksumme (Standard CosmWasm-Contract)
     * - dataLen == 38: 20-Byte-Payload → 32 bech32-Datenzeichen + 6 Checksumme (Standard Cosmos-Account)
     *
     * dataLen == 44 (26-Byte-Payload) wird NICHT akzeptiert — das ist keine gültige Cosmos-Adresslänge.
     */
    public fun isValidContract(address: String): Boolean {
        if (!isValid(address)) return false
        val sep = address.lowercase().lastIndexOf('1')
        val dataLen = address.length - sep - 1
        return dataLen == 58 || dataLen == 38
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if (((b ushr i) and 1) != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun verifyChecksum(
        hrp: String,
        data: IntArray,
    ): Boolean {
        val combined = hrpExpand(hrp) + data
        return polymod(combined) == 1
    }
}
