package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A first-class database index, embedded in the owning [ErmEntity].
 *
 * [attributeIds] order is significant for composite indexes (leftmost-prefix
 * matching semantics of most SQL dialects).
 *
 * V3.4.1
 */
@Serializable
data class ErmIndex(
    override val id: String,
    override val name: String?,
    val attributeIds: List<String>,
    val unique: Boolean = false,
    /**
     * Raw, dialect-neutral SQL boolean predicate for a partial/conditional index
     * (Postgres/SQLite `CREATE INDEX ... WHERE <where>`), emitted verbatim by
     * [dev.kuml.codegen.sql.ErmSqlEmitter] — `null` means an ordinary (non-partial)
     * index. Same trust model as [ErmCheckConstraint.expression] and
     * [ErmDataType.Custom.raw]: a trusted dialect-SQL fragment, not user input to
     * sanitize.
     */
    val where: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
