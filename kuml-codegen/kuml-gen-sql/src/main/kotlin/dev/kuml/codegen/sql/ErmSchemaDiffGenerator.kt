package dev.kuml.codegen.sql

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmView

/**
 * ADR-0016 (deferred item) — computes the additive-only [DiffOutcome] between
 * two [ErmModel] snapshots (`old` → `new`).
 *
 * **Identity is name-keyed, never id-keyed.** The `ermModel { … }` DSL
 * ([dev.kuml.erm.dsl.ErmModelBuilder]/[dev.kuml.erm.dsl.EntityBuilder]) assigns
 * positional synthetic ids (`attr_<entityIx>_<n>`, `idx_<entityIx>_<n>`, entity
 * ids by declaration order). Two independently-evaluated `.kuml.kts` files
 * therefore have unstable, non-comparable ids — inserting one entity shifts
 * every later id. Keying the diff on SQL names instead (`entity.name`,
 * `attribute.name`, an index's `name`/column-signature, `view.name`) sidesteps
 * that instability entirely, and has a convenient side effect: a rename
 * surfaces as *drop old name + add new name*, and the drop trips the
 * destructive-change guard below — so the diff never has to guess whether a
 * pair of changes is "really" a rename. Any doubt refuses.
 *
 * **Non-goals** (deliberately out of scope, not silently mis-handled):
 *  - [ErmModel.relationships] / [ErmModel.categories] changes are not diffed
 *    on their own — neither emits standalone DDL in [ErmSqlEmitter] (foreign
 *    keys come from [ErmAttribute.foreignKey], categories are IDEF1X-diagram
 *    sugar), so a genuinely new FK already arrives via its new/changed column.
 *  - `CREATE OR REPLACE VIEW` is data-safe but is a behavior change, so a
 *    changed view body is refused rather than silently rewritten.
 */
internal object ErmSchemaDiffGenerator {
    fun diff(
        old: ErmModel,
        new: ErmModel,
    ): DiffOutcome {
        val reasons = mutableListOf<String>()
        val newEntities = mutableListOf<ErmEntity>()
        val newAttributes = mutableListOf<AddedAttribute>()
        val newIndexes = mutableListOf<AddedIndex>()
        val newChecks = mutableListOf<AddedCheck>()

        val oldEntitiesByKey = old.entities.associateBy { entityKey(it) }
        val newEntitiesByKey = new.entities.associateBy { entityKey(it) }

        for (key in oldEntitiesByKey.keys) {
            if (key !in newEntitiesByKey) {
                reasons += "entity '$key' was removed — dropping a table is destructive"
            }
        }

        for ((key, newEntity) in newEntitiesByKey) {
            val oldEntity = oldEntitiesByKey[key]
            if (oldEntity == null) {
                newEntities += newEntity
                continue
            }
            val addedAttributeNames = diffAttributes(key, oldEntity, newEntity, reasons, newAttributes)
            diffIndexes(key, oldEntity, newEntity, reasons, newIndexes)
            diffChecks(key, oldEntity, newEntity, addedAttributeNames, reasons, newChecks)
        }

        val newViews = diffViews(old, new, reasons)

        return if (reasons.isNotEmpty()) {
            DiffOutcome.Refused(reasons)
        } else {
            DiffOutcome.Ok(ErmSchemaDiff(newEntities, newAttributes, newIndexes, newViews, newChecks))
        }
    }

    // ── Attributes ───────────────────────────────────────────────────────────

    /** @return the set of attribute names newly added on this entity by this diff (used by [diffChecks]'s new-column-only gate). */
    private fun diffAttributes(
        entityKey: String,
        old: ErmEntity,
        new: ErmEntity,
        reasons: MutableList<String>,
        out: MutableList<AddedAttribute>,
    ): Set<String> {
        val oldByKey = old.attributes.associateBy { attrKey(it) }
        val newByKey = new.attributes.associateBy { attrKey(it) }
        val addedNames = mutableSetOf<String>()

        for (key in oldByKey.keys) {
            if (key !in newByKey) {
                reasons += "column '$entityKey.$key' was removed — dropping a column is destructive"
            }
        }

        for ((key, newAttr) in newByKey) {
            val oldAttr = oldByKey[key]
            if (oldAttr == null) {
                if (!newAttr.nullable && newAttr.default == null && !newAttr.autoIncrement) {
                    reasons +=
                        "new column '$entityKey.$key' is NOT NULL without a DEFAULT — adding it to a populated " +
                        "table fails; make it nullable or add a default"
                } else {
                    out += AddedAttribute(new, newAttr)
                    addedNames += key
                }
                continue
            }

            val changes = mutableListOf<String>()
            if (oldAttr.type != newAttr.type) changes += "type"
            if (oldAttr.primaryKey != newAttr.primaryKey) changes += "primary key"
            if (oldAttr.autoIncrement != newAttr.autoIncrement) changes += "auto-increment"
            if (oldAttr.nullable && !newAttr.nullable) changes += "made NOT NULL"
            if (!oldAttr.unique && newAttr.unique) changes += "unique added"
            if (oldAttr.default != newAttr.default) changes += "default"
            if (oldAttr.foreignKey != newAttr.foreignKey) changes += "foreign key"
            if (changes.isNotEmpty()) {
                reasons +=
                    "column '$entityKey.$key' changed (${changes.joinToString(", ")}) — modifications require " +
                    "a hand-authored migration"
            }
        }
        return addedNames
    }

    // ── Indexes ──────────────────────────────────────────────────────────────

    private fun diffIndexes(
        entityKey: String,
        old: ErmEntity,
        new: ErmEntity,
        reasons: MutableList<String>,
        out: MutableList<AddedIndex>,
    ) {
        val oldByKey = old.indexes.associateBy { indexKey(old, it) }
        val newByKey = new.indexes.associateBy { indexKey(new, it) }

        for ((key, oldIndex) in oldByKey) {
            if (key !in newByKey) {
                reasons +=
                    "index '${indexDisplayName(old, oldIndex)}' on '$entityKey' was removed — dropping an index is destructive"
            }
        }

        for ((key, newIndex) in newByKey) {
            val oldIndex = oldByKey[key]
            if (oldIndex == null) {
                out += AddedIndex(new, newIndex)
                continue
            }
            val oldCols = indexColumnNames(old, oldIndex)
            val newCols = indexColumnNames(new, newIndex)
            if (oldCols != newCols || oldIndex.unique != newIndex.unique) {
                reasons +=
                    "index '${indexDisplayName(new, newIndex)}' on '$entityKey' changed (columns or uniqueness) — " +
                    "modifications require a hand-authored migration"
            }
        }
    }

    private fun indexDisplayName(
        entity: ErmEntity,
        index: ErmIndex,
    ): String = index.name ?: "(${indexColumnNames(entity, index).joinToString(", ")})"

    private fun indexColumnNames(
        entity: ErmEntity,
        index: ErmIndex,
    ): List<String> = index.attributeIds.map { id -> entity.attributes.firstOrNull { it.id == id }?.name ?: id }

    // ── Checks ───────────────────────────────────────────────────────────────

    private fun diffChecks(
        entityKey: String,
        old: ErmEntity,
        new: ErmEntity,
        addedAttributeNames: Set<String>,
        reasons: MutableList<String>,
        out: MutableList<AddedCheck>,
    ) {
        val oldByKey = old.checks.associateBy { checkKey(it) }
        val newByKey = new.checks.associateBy { checkKey(it) }

        for (key in oldByKey.keys) {
            if (key !in newByKey) {
                reasons += "check constraint '$key' on '$entityKey' was removed — dropping a constraint is destructive"
            }
        }

        for ((key, newCheck) in newByKey) {
            val oldCheck = oldByKey[key]
            if (oldCheck == null) {
                val allColumnNames = new.attributes.mapNotNull { it.name }.toSet()
                val referencedIdentifiers = tokenizeIdentifiers(newCheck.expression)
                val referencedExistingColumns =
                    referencedIdentifiers.filter { it in allColumnNames && it !in addedAttributeNames }
                if (referencedExistingColumns.isNotEmpty()) {
                    reasons +=
                        "new CHECK on '$entityKey' references existing column(s) " +
                        "(${referencedExistingColumns.joinToString(", ")}) — may fail against existing data; " +
                        "author manually"
                } else {
                    out += AddedCheck(new, newCheck)
                }
                continue
            }
            if (oldCheck.expression.trim() != newCheck.expression.trim()) {
                reasons += "check constraint '$key' on '$entityKey' changed — modifications require a hand-authored migration"
            }
        }
    }

    /**
     * Whole-word identifier tokenization of a raw SQL boolean expression — the
     * new-column-only safety gate for [diffChecks]. Deliberately not a SQL
     * parser: this is a conservative heuristic (over-matches string/comment
     * content as "identifiers", which only ever makes the gate *more* likely to
     * refuse, never less), documented here rather than hidden as an implicit
     * assumption.
     */
    private fun tokenizeIdentifiers(expression: String): Set<String> = IDENTIFIER_REGEX.findAll(expression).map { it.value }.toSet()

    // ── Views ────────────────────────────────────────────────────────────────

    private fun diffViews(
        old: ErmModel,
        new: ErmModel,
        reasons: MutableList<String>,
    ): List<ErmView> {
        val newViews = mutableListOf<ErmView>()
        val oldByKey = old.views.associateBy { viewKey(it) }
        val newByKey = new.views.associateBy { viewKey(it) }

        for (key in oldByKey.keys) {
            if (key !in newByKey) {
                reasons += "view '$key' was removed — dropping a view is destructive"
            }
        }

        for ((key, newView) in newByKey) {
            val oldView = oldByKey[key]
            if (oldView == null) {
                newViews += newView
                continue
            }
            if (oldView.query.trim() != newView.query.trim()) {
                reasons += "view '$key' body changed — refusing to guess; author a CREATE OR REPLACE migration manually"
            }
        }
        return newViews
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private fun entityKey(entity: ErmEntity): String = entity.name ?: entity.id

    private fun attrKey(attr: ErmAttribute): String = attr.name ?: attr.id

    private fun viewKey(view: ErmView): String = view.name ?: view.id

    private fun checkKey(check: ErmCheckConstraint): String = check.name ?: check.expression.trim()

    /**
     * Named index → keyed by name. Unnamed index → keyed by a
     * `unique|col1,col2,…` signature (column names resolved through [entity],
     * order-preserving) — matches the same identity two structurally-identical
     * unnamed indexes would resolve to.
     */
    private fun indexKey(
        entity: ErmEntity,
        index: ErmIndex,
    ): String = index.name?.let { "name:$it" } ?: "sig:${index.unique}|${indexColumnNames(entity, index).joinToString(",")}"

    private val IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
