package dev.kuml.erm.model

import kotlinx.serialization.Serializable

/**
 * Referential action taken by the database when the referenced row of a
 * foreign key changes ([onUpdate]) or is deleted ([onDelete]).
 *
 * Mirrors the standard SQL `REFERENTIAL_CONSTRAINT_TRIGGER_ACTION` vocabulary
 * so the future SQL-dialect code generator (V3.4.7) can map it 1:1.
 *
 * V3.4.1
 */
@Serializable
enum class ReferentialAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

/**
 * Marks an [ErmAttribute] as a foreign key.
 *
 * [targetAttributeId] `null` means "the primary key of the target entity" —
 * this is the overwhelmingly common case and keeps DSL usage terse.
 *
 * V3.4.1
 */
@Serializable
data class ErmForeignKey(
    val targetEntityId: String,
    val targetAttributeId: String? = null,
    val onDelete: ReferentialAction = ReferentialAction.NO_ACTION,
    val onUpdate: ReferentialAction = ReferentialAction.NO_ACTION,
)
