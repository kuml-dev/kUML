package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrale Farbe als 24-bit RGB (kein Alpha-Channel in V1).
 *
 * Wird direkt vom SVG-Renderer als Hex-String verbraucht und vom Compose-Adapter
 * in [androidx.compose.ui.graphics.Color] gewandelt.
 *
 * Beispiel:
 * ```kotlin
 * val red = KumlColor(0xFF0000)
 * println(red.toHex()) // "#FF0000"
 * ```
 */
@Serializable
public data class KumlColor(
    /** 24-bit RGB-Wert (Bits 0–23; höhere Bits werden maskiert). */
    public val rgb: Int,
) {
    /**
     * Gibt die Farbe als CSS-Hex-String zurück, z.B. `"#FF0000"`.
     *
     * Der Mask `and 0xFFFFFF` stellt sicher, dass negative Int-Werte korrekt
     * auf 6 Hex-Ziffern begrenzt werden.
     */
    public fun toHex(): String {
        val masked = rgb and 0xFFFFFF
        val hex = masked.toString(16).uppercase().padStart(6, '0')
        return "#$hex"
    }

    public companion object {
        /** Reines Schwarz (`#000000`). */
        public val Black: KumlColor = KumlColor(0x000000)

        /** Reines Weiß (`#FFFFFF`). */
        public val White: KumlColor = KumlColor(0xFFFFFF)
    }
}
