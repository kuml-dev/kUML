package dev.kuml.runtime.chain.cosmos

import java.util.Base64

/**
 * V3.0.21 — Thin-Wrapper um java.util.Base64 (Standard-Base64, URL-safe off).
 *
 * Cosmos-SDK/Tendermint liefert Event-Attribute und smart-query-Resultate als
 * Standard-Base64 (RFC 4648, Tabelle 1, mit Padding). Dieser Codec kapselt
 * encode/decode und liefert lesbare Fehlermeldungen bei ungültigen Eingaben.
 */
public object Base64Codec {
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val urlSafeEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()
    private val urlSafeDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Kodiert [bytes] als Standard-Base64-String (mit Padding). */
    public fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Kodiert [bytes] als URL-safe Base64-String (ohne Padding, RFC 4648 Tabelle 2).
     * Wird für Cosmos LCD REST-Endpunkte verwendet, wo Base64 als URL-Parameter erscheint.
     */
    public fun encodeUrlSafe(bytes: ByteArray): String = urlSafeEncoder.encodeToString(bytes)

    /**
     * Dekodiert einen Standard-Base64-String.
     *
     * @throws IllegalArgumentException wenn [b64] kein gültiges Base64 ist.
     */
    public fun decode(b64: String): ByteArray =
        try {
            decoder.decode(b64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 string: '${b64.take(32)}…'", e)
        }
}
