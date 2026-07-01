package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.RelativeConstraint

/**
 * Liest `kuml.layout.*`-Schlüssel aus einer `metadata`-Map zurück in [NodeHints].
 *
 * Defensiv: falscher Wert-Typ, fehlende Sub-Keys, ungültige Constraint-Kinds —
 * alles wird schweigend ignoriert und liefert die Defaults.
 */
internal object HintsReader {
    fun read(metadata: Map<String, KumlMetaValue>): NodeHints {
        val gridCol = metadata.intOrNull(BridgeLayoutKeys.GRID_COL)
        val gridRow = metadata.intOrNull(BridgeLayoutKeys.GRID_ROW)
        val gridColSpan = metadata.intOrNull(BridgeLayoutKeys.GRID_COL_SPAN) ?: 1
        val gridRowSpan = metadata.intOrNull(BridgeLayoutKeys.GRID_ROW_SPAN) ?: 1
        val pinned = metadata.flagOrFalse(BridgeLayoutKeys.PINNED)
        val relative = metadata.relativeList(BridgeLayoutKeys.RELATIVE)
        return NodeHints(gridCol, gridRow, gridColSpan, gridRowSpan, pinned, relative)
    }

    private fun Map<String, KumlMetaValue>.intOrNull(key: String): Int? = (this[key] as? KumlMetaValue.Integer)?.value?.toInt()

    private fun Map<String, KumlMetaValue>.flagOrFalse(key: String): Boolean = (this[key] as? KumlMetaValue.Flag)?.value ?: false

    private fun Map<String, KumlMetaValue>.relativeList(key: String): List<RelativeConstraint> {
        val items = (this[key] as? KumlMetaValue.Items)?.value ?: return emptyList()
        return items.mapNotNull { item ->
            val entries = (item as? KumlMetaValue.Entries)?.value ?: return@mapNotNull null
            val kind =
                (entries[BridgeLayoutKeys.REL_KIND] as? KumlMetaValue.Text)?.value
                    ?: return@mapNotNull null
            val other =
                (entries[BridgeLayoutKeys.REL_OTHER] as? KumlMetaValue.Text)?.value
                    ?: return@mapNotNull null
            when (kind) {
                "above" -> RelativeConstraint.Above(NodeId(other))
                "below" -> RelativeConstraint.Below(NodeId(other))
                "leftOf" -> RelativeConstraint.LeftOf(NodeId(other))
                "rightOf" -> RelativeConstraint.RightOf(NodeId(other))
                "sameRowAs" -> RelativeConstraint.SameRowAs(NodeId(other))
                "sameColAs" -> RelativeConstraint.SameColAs(NodeId(other))
                else -> null
            }
        }
    }
}
