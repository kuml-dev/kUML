package dev.kuml.layout.bridge

/**
 * Spiegel der Schlüssel aus `dev.kuml.core.dsl.layout.LayoutMetadataKeys`.
 *
 * Duplikat statt Cross-Modul-Abhängigkeit — die Bridge darf nicht auf `kuml-core-dsl`
 * hängen, da sie Renderer-seitig ist. Tests verifizieren die String-Identität
 * (alle Werte starten mit `kuml.layout`).
 */
internal object BridgeLayoutKeys {
    const val GRID_COL = "kuml.layout.gridCol"
    const val GRID_ROW = "kuml.layout.gridRow"
    const val GRID_COL_SPAN = "kuml.layout.gridColSpan"
    const val GRID_ROW_SPAN = "kuml.layout.gridRowSpan"
    const val PINNED = "kuml.layout.pinned"
    const val RELATIVE = "kuml.layout.relative"
    const val REL_KIND = "kind"
    const val REL_OTHER = "other"
}
