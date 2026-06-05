package dev.kuml.layout.bridge

import dev.kuml.layout.Size

/**
 * Liefert die intrinsische Größe eines Elements. Wird vom Renderer bereitgestellt,
 * der Text-Metriken kennt. Die Bridge ruft ihn pro Knoten genau einmal auf.
 *
 * Beispiel:
 * ```kotlin
 * val bridge = UmlLayoutBridge.toLayoutGraph(diagram, SizeProvider.constant(200f, 100f))
 * ```
 */
public fun interface SizeProvider {
    /**
     * Gibt die intrinsische Größe des Elements mit der gegebenen ID und dem gegebenen Kind zurück.
     *
     * @param elementId Stabile ID des Elements (aus dem Modell).
     * @param elementKind `element::class.simpleName` — z.B. `"UmlClass"`, `"C4Container"`.
     */
    public fun sizeOf(
        elementId: String,
        elementKind: String,
    ): Size

    public companion object {
        /**
         * Konstante Default-Größe — sinnvoll für Tests und V1.
         *
         * @param width Breite in abstrakten Pixeln (Default: 160).
         * @param height Höhe in abstrakten Pixeln (Default: 80).
         */
        public fun constant(
            width: Float = 160f,
            height: Float = 80f,
        ): SizeProvider = SizeProvider { _, _ -> Size(width, height) }
    }
}
