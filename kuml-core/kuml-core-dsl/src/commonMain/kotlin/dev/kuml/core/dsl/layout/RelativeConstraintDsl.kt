package dev.kuml.core.dsl.layout

import dev.kuml.core.model.KumlMetaValue

/**
 * Interne Repräsentation eines relativen Positionierungsconstraints in der DSL.
 *
 * Wird von [LayoutHintsBuilder] gesammelt und per [toMeta] in [KumlMetaValue.Entries]
 * materialisiert. Alle Sub-Typen liegen in dieser Datei (sealed-Einschränkung).
 */
internal sealed interface RelativeConstraintDsl {
    /** Materialisiert diesen Constraint als [KumlMetaValue.Entries]-Eintrag. */
    fun toMeta(): KumlMetaValue

    data class Above(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("above"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }

    data class Below(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("below"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }

    data class LeftOf(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("leftOf"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }

    data class RightOf(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("rightOf"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }

    data class SameRowAs(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("sameRowAs"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }

    data class SameColAs(
        val otherId: String,
    ) : RelativeConstraintDsl {
        override fun toMeta(): KumlMetaValue =
            KumlMetaValue.Entries(
                mapOf(
                    LayoutMetadataKeys.REL_KIND to KumlMetaValue.Text("sameColAs"),
                    LayoutMetadataKeys.REL_OTHER to KumlMetaValue.Text(otherId),
                ),
            )
    }
}
