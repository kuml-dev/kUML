package dev.kuml.runtime.chain.evm

/**
 * Minimaler ABI-Decoder für die drei Contract-Getter in [EvmChainAdapter.connect].
 *
 * Interne Hilfsklasse — nicht public, explicitApi() greift nicht.
 * Nur read-only, keine ABI-Encoding-Funktionalität.
 */
internal object AbiCodec {
    /**
     * Dekodiert ein einzelnes `bytes32`-Return-Value aus einem 0x-Hex-Callresult.
     * Die ABI-Antwort ist 32 Bytes (ein Slot). Gibt die 32 Bytes zurück.
     */
    fun decodeBytes32(hexResult: String): ByteArray {
        val bytes = EvmEventDecoder.hexToBytes(hexResult)
        if (bytes.size < 32) {
            throw EvmChainAdapterException.MalformedResponse(
                "Expected 32-byte ABI result, got ${bytes.size} bytes",
            )
        }
        return bytes.copyOfRange(0, 32)
    }

    /**
     * Dekodiert ein `uint256`-Return-Value (ABI 32-Byte-Slot) als Int.
     */
    fun decodeUint(hexResult: String): Int {
        val bytes = EvmEventDecoder.hexToBytes(hexResult)
        if (bytes.size < 32) {
            throw EvmChainAdapterException.MalformedResponse(
                "Expected 32-byte ABI result for uint, got ${bytes.size} bytes",
            )
        }
        // Take last 4 bytes as uint32
        val slot = bytes.copyOfRange(bytes.size - 32, bytes.size)
        return ((slot[28].toInt() and 0xFF) shl 24) or
            ((slot[29].toInt() and 0xFF) shl 16) or
            ((slot[30].toInt() and 0xFF) shl 8) or
            (slot[31].toInt() and 0xFF)
    }

    /**
     * Dekodiert ein ABI-encoded dynamisches `string`-Return-Value.
     *
     * ABI-Layout für ein einzelnes dynamisch-String-Return:
     *   word 0: offset (0x20 = 32 → zeigt auf word 1)
     *   word 1: length in bytes
     *   words 2+: UTF-8-Daten (right-padded to 32-byte boundary)
     *
     * Sicherheit: Alle Offset- und Length-Werte werden als Long ausgelesen und
     * geprüft, um Integer-Overflow-Angriffe (Bounds-Check-Bypass via Wrap-around)
     * zu verhindern. Ein böswillig konstruierter offset nahe Int.MAX_VALUE würde
     * mit 32-Bit-Arithmetik in einen negativen Wert übergehen und die Prüfung
     * umgehen — mit Long-Arithmetik ist das ausgeschlossen.
     *
     * Zusätzlich wird die String-Länge auf MAX_STRING_BYTES begrenzt, um
     * Memory-Exhaustion durch überdimensionierte RPC-Antworten zu verhindern.
     */
    fun decodeString(hexResult: String): String {
        val bytes = EvmEventDecoder.hexToBytes(hexResult)
        if (bytes.size < 64) {
            throw EvmChainAdapterException.MalformedResponse(
                "ABI string result too short: ${bytes.size} bytes",
            )
        }
        // offset word: should be 32 (0x20), pointing to the length word.
        // Alle Größen als Long lesen, um Integer-Overflow zu verhindern.
        val offsetBytes = bytes.copyOfRange(0, 32)
        val offset = readUint32AsLong(offsetBytes, 28)
        if (offset < 32L || offset + 32L > bytes.size.toLong()) {
            throw EvmChainAdapterException.MalformedResponse(
                "ABI string offset $offset out of range for result of ${bytes.size} bytes",
            )
        }
        val offsetInt = offset.toInt() // sicher: geprüft < bytes.size <= Int.MAX_VALUE
        val lengthBytes = bytes.copyOfRange(offsetInt, offsetInt + 32)
        val length = readUint32AsLong(lengthBytes, 28)
        if (length > MAX_STRING_BYTES) {
            throw EvmChainAdapterException.MalformedResponse(
                "ABI string length $length exceeds maximum allowed $MAX_STRING_BYTES bytes",
            )
        }
        val dataStart = offset + 32L
        if (dataStart + length > bytes.size.toLong()) {
            throw EvmChainAdapterException.MalformedResponse(
                "ABI string length $length extends beyond result buffer",
            )
        }
        val dataStartInt = dataStart.toInt() // sicher: geprüft < bytes.size
        val lengthInt = length.toInt() // sicher: length <= MAX_STRING_BYTES <= Int.MAX_VALUE
        return String(bytes.copyOfRange(dataStartInt, dataStartInt + lengthInt), Charsets.UTF_8)
    }

    /**
     * Liest 4 Bytes ab [byteOffset] als vorzeichenlosen 32-Bit-Wert und gibt
     * das Ergebnis als Long zurück (kein Vorzeichen-Overflow möglich).
     */
    private fun readUint32AsLong(
        bytes: ByteArray,
        byteOffset: Int,
    ): Long =
        ((bytes[byteOffset].toLong() and 0xFFL) shl 24) or
            ((bytes[byteOffset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[byteOffset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[byteOffset + 3].toLong() and 0xFFL)

    /** Maximale erlaubte String-Länge in ABI-Dekodierung (1 MB). */
    internal const val MAX_STRING_BYTES = 1_048_576L
}
