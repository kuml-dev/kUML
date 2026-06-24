package dev.kuml.runtime.chain.wasm.scale

/**
 * V3.0.22 — Minimaler SCALE-Codec (Substrate "Simple Concatenated Aggregate Little-Endian").
 *
 * **Pure Kotlin, Native-Image-tauglich**: keine externen Libraries, kein Reflection,
 * keine kotlinx-coroutines. Plugin-Autoren, die einen eigenen WASM-/Substrate-Adapter
 * bauen wollen, koennen diesen Codec direkt verwenden, ohne ein schwergewichtiges
 * Polkadot-SDK einzubinden.
 *
 * Unterstuetzter Funktionsumfang (bewusst auf das fuer ink!-Events Noetige beschraenkt):
 * - Fixed-width unsigned Integer: u8, u16, u32, u64, u128 (Little-Endian)
 * - Compact<u32> / Compact<u64> (SCALE Compact/General-Integer-Encoding)
 * - bool
 * - Vec<T> (Compact-Laenge gefolgt von n Elementen)
 * - Option<T> (0x00 = None, 0x01 = Some(T))
 * - Result<T, E> (0x00 = Ok(T), 0x01 = Err(E))
 * - feste Byte-Arrays (z.B. AccountId32, H256)
 *
 * Der Codec ist absichtlich NICHT generisch ueber die volle SCALE-Typregistrierung —
 * stattdessen liest [ScaleReader] primitive Typen, und [InkEventDecoder] kombiniert sie
 * anhand der ink!-ABI-Typdefinitionen zu strukturierten Werten.
 *
 * Sicherheit / DoS-Haertung:
 * - Compact-Laengen werden gegen [maxCollectionLen] geprueft, bevor allokiert wird —
 *   ein boeswillig grosser Compact-Prefix kann so keine OOM-Allokation ausloesen.
 * - Jeder Lesevorgang prueft die Restlaenge; Unterlauf wirft [ScaleException], nie eine
 *   rohe [IndexOutOfBoundsException].
 */
public object ScaleCodec {
    /**
     * Default-Obergrenze fuer Compact-praefixierte Collection-Laengen (Vec, Bytes).
     * Schuetzt vor DoS durch boeswillig grosse Laengen-Prefixe.
     */
    public const val DEFAULT_MAX_COLLECTION_LEN: Int = 1 shl 20 // 1 Mi Elemente

    // ---------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------

    public fun encodeU8(value: Int): ByteArray {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        return byteArrayOf(value.toByte())
    }

    public fun encodeU16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        return byteArrayOf(value.toByte(), (value ushr 8).toByte())
    }

    public fun encodeU32(value: Long): ByteArray {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range: $value" }
        return ByteArray(4) { i -> (value ushr (8 * i)).toByte() }
    }

    public fun encodeU64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (8 * i)).toByte() }

    /** SCALE Compact-Encoding fuer nicht-negative Werte (single/two/four-byte/big-integer mode). */
    public fun encodeCompact(value: Long): ByteArray {
        require(value >= 0) { "Compact value must be non-negative: $value" }
        return when {
            value < 0x40 -> byteArrayOf((value shl 2).toByte()) // single-byte mode (00)
            value < 0x4000 -> {
                val v = (value shl 2) or 0b01
                byteArrayOf(v.toByte(), (v ushr 8).toByte())
            }
            value < 0x4000_0000 -> {
                val v = (value shl 2) or 0b10
                ByteArray(4) { i -> (v ushr (8 * i)).toByte() }
            }
            else -> {
                // big-integer mode (11): lower 6 bits = (#bytes - 4).
                // value is a non-negative Long, so the maximum payload is 8 bytes (for Long.MAX_VALUE).
                // Values requiring more than 8 payload bytes (u128 > Long.MAX_VALUE) cannot be represented
                // as a Kotlin Long and must be passed via a dedicated BigInteger overload — reject here to
                // avoid silently producing a corrupt header byte.
                var v = value
                val bytes = ArrayList<Byte>(8)
                while (v > 0) {
                    bytes.add((v and 0xFF).toByte())
                    v = v ushr 8
                }
                // bytes.size is in [5..8] here (values < 2^32 were handled by four-byte mode above).
                val modeField = bytes.size - 4 // range [1..4], fits in 6 lower-bits safely
                val header = ((modeField shl 2) or 0b11).toByte()
                ByteArray(1 + bytes.size).also { out ->
                    out[0] = header
                    for (i in bytes.indices) out[1 + i] = bytes[i]
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Decoding entry points
    // ---------------------------------------------------------------------

    /** Erzeugt einen [ScaleReader] ueber [data] mit optionaler Collection-Laengen-Obergrenze. */
    public fun reader(
        data: ByteArray,
        maxCollectionLen: Int = DEFAULT_MAX_COLLECTION_LEN,
    ): ScaleReader = ScaleReader(data, maxCollectionLen)
}

/**
 * Sequentieller SCALE-Leser ueber ein [ByteArray]. Nicht thread-safe — pro Decode-Vorgang
 * eine frische Instanz verwenden. Alle Lesevorgaenge sind bounds-checked.
 *
 * @property maxCollectionLen Obergrenze fuer Compact-praefixierte Laengen (DoS-Schutz).
 */
public class ScaleReader(
    private val data: ByteArray,
    private val maxCollectionLen: Int = ScaleCodec.DEFAULT_MAX_COLLECTION_LEN,
) {
    private var pos: Int = 0

    /** Anzahl noch nicht gelesener Bytes. */
    public val remaining: Int get() = data.size - pos

    /** Aktuelle Leseposition (fuer Diagnostik). */
    public val position: Int get() = pos

    private fun require(n: Int) {
        if (n < 0) throw ScaleException("Negative read length: $n")
        if (remaining < n) {
            throw ScaleException("SCALE underflow: need $n bytes at pos $pos, but only $remaining remaining")
        }
    }

    public fun readU8(): Int {
        require(1)
        return data[pos++].toInt() and 0xFF
    }

    public fun readBool(): Boolean =
        when (val b = readU8()) {
            0 -> false
            1 -> true
            else -> throw ScaleException("Invalid bool discriminant: $b")
        }

    public fun readU16(): Int {
        require(2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    public fun readU32(): Long {
        require(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    public fun readU64(): Long {
        require(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    /**
     * Liest u128 als 0x-praefixierten 32-Hex-Zeichen-String (Big-Endian, kanonisch).
     * Die Ausgabe ist immer exakt 34 Zeichen lang ("0x" + 32 Hex-Ziffern) — auch fuer 0.
     * Kotlin/JVM ohne BigInteger-Import im Native-Pfad: wir geben den Rohwert als Hex
     * zurueck, damit Aufrufer ihn nach Bedarf in eine BigInteger parsen koennen.
     *
     * Kanonisches Format (kein trimStart): interoperabel mit Wallet-UIs und Balance-Displays,
     * die eine feste 32-Hex-Darstellung erwarten.
     */
    public fun readU128Hex(): String {
        require(16)
        // SCALE ist Little-Endian; fuer einen lesbaren Hex-String drehen wir auf Big-Endian.
        val sb = StringBuilder(32)
        for (i in 15 downTo 0) sb.append("%02x".format(data[pos + i]))
        pos += 16
        return "0x" + sb.toString()
    }

    /** Liest ein festes Byte-Array der Laenge [n] (z.B. AccountId32 = 32, H256 = 32). */
    public fun readFixedBytes(n: Int): ByteArray {
        require(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** SCALE Compact-Decoding (single/two/four-byte/big-integer mode). */
    public fun readCompact(): Long {
        require(1)
        val first = data[pos].toInt() and 0xFF
        return when (first and 0b11) {
            0b00 -> {
                pos += 1
                (first ushr 2).toLong()
            }
            0b01 -> {
                require(2)
                val v = ((data[pos].toLong() and 0xFF) or ((data[pos + 1].toLong() and 0xFF) shl 8))
                pos += 2
                v ushr 2
            }
            0b10 -> {
                require(4)
                var v = 0L
                for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                pos += 4
                v ushr 2
            }
            else -> {
                val numBytes = (first ushr 2) + 4
                if (numBytes > 8) {
                    throw ScaleException("Compact big-integer mode wider than u64 not supported ($numBytes bytes)")
                }
                pos += 1
                require(numBytes)
                var v = 0L
                for (i in 0 until numBytes) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                pos += numBytes
                v
            }
        }
    }

    /**
     * Liest eine Compact-praefixierte Laenge und prueft sie gegen [maxCollectionLen].
     * @throws ScaleException wenn die Laenge die Obergrenze ueberschreitet (DoS-Schutz) oder
     *   die deklarierte Laenge die verbleibenden Bytes uebersteigt.
     */
    public fun readCollectionLen(): Int {
        val len = readCompact()
        if (len < 0 || len > maxCollectionLen) {
            throw ScaleException("Collection length $len exceeds max $maxCollectionLen (possible DoS)")
        }
        return len.toInt()
    }

    /** Liest Vec<u8> (Compact-Laenge + Bytes). */
    public fun readByteVec(): ByteArray {
        val len = readCollectionLen()
        require(len)
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    /** Liest Vec<T> via [element]-Reader. */
    public fun <T> readVec(element: (ScaleReader) -> T): List<T> {
        val len = readCollectionLen()
        val out = ArrayList<T>(minOf(len, 1024)) // initial cap gedeckelt, waechst bei Bedarf
        repeat(len) { out.add(element(this)) }
        return out
    }

    /** Liest Option<T> (0x00 = None, 0x01 = Some). */
    public fun <T> readOption(element: (ScaleReader) -> T): T? =
        when (val tag = readU8()) {
            0 -> null
            1 -> element(this)
            else -> throw ScaleException("Invalid Option discriminant: $tag")
        }

    /** Liest Result<T, E> (0x00 = Ok, 0x01 = Err) als [ScaleResult]. */
    public fun <T, E> readResult(
        ok: (ScaleReader) -> T,
        err: (ScaleReader) -> E,
    ): ScaleResult<T, E> =
        when (val tag = readU8()) {
            0 -> ScaleResult.Ok(ok(this))
            1 -> ScaleResult.Err(err(this))
            else -> throw ScaleException("Invalid Result discriminant: $tag")
        }

    /** Liest einen SCALE-kodierten UTF-8 String (Vec<u8>). */
    public fun readString(): String = String(readByteVec(), Charsets.UTF_8)
}

/** SCALE Result<T, E> als sealed-Typ (kein Verwechseln mit kotlin.Result). */
public sealed interface ScaleResult<out T, out E> {
    public data class Ok<out T>(
        public val value: T,
    ) : ScaleResult<T, Nothing>

    public data class Err<out E>(
        public val error: E,
    ) : ScaleResult<Nothing, E>
}

/** Wird bei SCALE-Dekodierfehlern (Underflow, ungueltiger Diskriminator, DoS-Laenge) geworfen. */
public class ScaleException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
