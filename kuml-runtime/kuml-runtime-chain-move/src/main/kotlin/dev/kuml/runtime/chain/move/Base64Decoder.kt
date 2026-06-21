package dev.kuml.runtime.chain.move

import java.util.Base64

/**
 * V3.0.20 — Pure-JVM-Base64-Dekodierung für Sui-BCS-Event-Daten.
 *
 * Sui liefert `parsedJson`-freie BCS-Event-Payloads als Base64 (Standard-Alphabet,
 * mit Padding). Kapselt [java.util.Base64] mit kUML-spezifischer Fehler-Semantik.
 */
public object Base64Decoder {
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /**
     * Dekodiert Standard-Base64. Leerer String → leeres Array.
     *
     * @throws IllegalArgumentException bei ungültigem Base64.
     */
    public fun decode(base64: String): ByteArray {
        if (base64.isEmpty()) return ByteArray(0)
        return try {
            decoder.decode(base64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 string: '$base64'", e)
        }
    }
}
