package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrale Rahmen- und Strich-Maße für ein [KumlTheme].
 *
 * `cornerRadiusPx` ist der Standard-Rundungsradius für Knoten-Formen.
 * State-Knoten überschreiben diesen lokal mit `12px`.
 *
 * Beispiel:
 * ```kotlin
 * val borders = PlainTheme().borders
 * println(borders.regularPx) // 1.5
 * ```
 *
 * @property thinPx Dünner Strich — 1 px.
 * @property regularPx Standard-Strich — 1.5 px.
 * @property thickPx Breiter Strich (C4-SoftwareSystem-Rahmen) — 2 px.
 * @property cornerRadiusPx Standard-Eckradius für gerundete Rechtecke.
 */
@Serializable
public data class KumlBorders(
    public val thinPx: Float,
    public val regularPx: Float,
    public val thickPx: Float,
    public val cornerRadiusPx: Float,
)
