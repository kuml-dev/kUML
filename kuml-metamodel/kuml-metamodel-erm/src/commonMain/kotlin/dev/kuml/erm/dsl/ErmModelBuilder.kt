package dev.kuml.erm.dsl

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmNote
import dev.kuml.erm.model.ErmNoteLink
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ErmView
import dev.kuml.erm.model.RelationshipKind

/**
 * Root builder for an [ErmModel].
 *
 * Auto-ids are deterministic (`entity_0`, `rel_1`, `view_2`, …) — never
 * UUIDs — so snapshot/diff tests stay stable.
 *
 * If the block declares no [diagram], [build] synthesizes a default one
 * (name = model name, notation = MARTIN) projecting the whole model. Without
 * this, an `ermModel { … }` script with no explicit `diagram(…)` call would
 * produce `diagrams = emptyList()`, and `DiagramExtractor` would then reject
 * the script as "did not produce a renderable diagram" — surprising and not
 * LLM-friendly, since every other kUML DSL requires at least one diagram.
 *
 * V3.4.1
 */
@ErmDsl
class ErmModelBuilder(
    private val name: String,
) {
    private val entities = mutableListOf<ErmEntity>()
    private val relationships = mutableListOf<ErmRelationship>()
    private val views = mutableListOf<ErmView>()
    private val diagrams = mutableListOf<ErmDiagram>()
    private val categories = mutableListOf<ErmCategory>()
    private val notes = mutableListOf<ErmNote>()
    private val noteLinks = mutableListOf<ErmNoteLink>()

    /** Declares an entity (table). [weak] marks it as a weak entity (see [ErmEntity.weak]). */
    fun entity(
        name: String,
        weak: Boolean = false,
        block: EntityBuilder.() -> Unit,
    ): String {
        val entityIx = entities.size
        val id = autoId(prefix = "entity", n = entityIx)
        val builder = EntityBuilder(entityId = id, entityIx = entityIx, model = this)
        builder.apply(block)
        entities += builder.build(name = name, weak = weak)
        return id
    }

    /** Declares a relationship between two entities (by id). */
    fun relationship(
        from: String,
        to: String,
        name: String? = null,
        sourceCardinality: Cardinality = Cardinality.ONE,
        targetCardinality: Cardinality = Cardinality.ZERO_MANY,
        kind: RelationshipKind = RelationshipKind.NON_IDENTIFYING,
        sourceRole: String? = null,
        targetRole: String? = null,
    ): String {
        val id = autoId(prefix = "rel", n = relationships.size)
        relationships +=
            ErmRelationship(
                id = id,
                name = name,
                sourceEntityId = from,
                targetEntityId = to,
                sourceCardinality = sourceCardinality,
                targetCardinality = targetCardinality,
                kind = kind,
                sourceRole = sourceRole,
                targetRole = targetRole,
            )
        return id
    }

    /** Declares a first-class database view. */
    fun view(
        name: String,
        query: String,
        references: List<String> = emptyList(),
    ): String {
        val id = autoId(prefix = "view", n = views.size)
        views += ErmView(id = id, name = name, query = query, referencedEntityIds = references)
        return id
    }

    /**
     * Declares an IDEF1X category (subtype) cluster: [supertype] specialises
     * into [subtypes], each a mutually-exclusive category entity. [complete]
     * marks the cluster with a double completeness bar (every supertype row
     * belongs to exactly one subtype); [discriminator] optionally names the
     * supertype attribute (by id) that selects the subtype.
     */
    fun category(
        supertype: String,
        subtypes: List<String>,
        name: String? = null,
        complete: Boolean = false,
        discriminator: String? = null,
    ): String {
        val id = autoId(prefix = "category", n = categories.size)
        categories +=
            ErmCategory(
                id = id,
                name = name,
                supertypeEntityId = supertype,
                subtypeEntityIds = subtypes,
                complete = complete,
                discriminatorAttributeId = discriminator,
            )
        return id
    }

    /**
     * Declares a free-text note, anchored to entities / views (by id; none =
     * free-standing). Returns the note id (`note_<n>`). Anchors are checked in
     * [build]: unknown ids and anchors that are neither entity nor view throw.
     */
    fun note(
        text: String,
        vararg anchors: String,
        id: String? = null,
    ): String {
        val noteId = id ?: nextNoteId()
        notes += ErmNote(note = KumlNote(id = noteId, body = text))
        anchors.distinct().forEachIndexed { k, target ->
            noteLinks += ErmNoteLink(link = KumlNoteLink(id = "note_link_${notes.size - 1}_${k + 1}", noteId = noteId, targetId = target))
        }
        return noteId
    }

    /** Declares a diagram projection over this model. */
    fun diagram(
        name: String,
        notation: ErmNotation = ErmNotation.MARTIN,
        showViews: Boolean = true,
        showIndexes: Boolean = false,
    ) {
        diagrams += ErmDiagram(name = name, notation = notation, showViews = showViews, showIndexes = showIndexes)
    }

    /** Infix convenience: `customers oneToMany orders`. Returns the target id for chaining. */
    infix fun String.oneToMany(target: String): String {
        relationship(from = this, to = target, sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
        return target
    }

    /** Infix convenience: `students manyToMany courses`. Returns the target id for chaining. */
    infix fun String.manyToMany(target: String): String {
        relationship(from = this, to = target, sourceCardinality = Cardinality.ZERO_MANY, targetCardinality = Cardinality.ZERO_MANY)
        return target
    }

    /** Infix convenience: `user oneToOne profile`. Returns the target id for chaining. */
    infix fun String.oneToOne(target: String): String {
        relationship(from = this, to = target, sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_ONE)
        return target
    }

    /** Looks up an already-declared entity by id — used by [EntityBuilder.foreignKey] for type inference. */
    internal fun entityById(id: String): ErmEntity? = entities.firstOrNull { it.id == id }

    private fun nextNoteId(): String {
        val used =
            buildSet {
                notes.forEach { add(it.id) }
                entities.forEach { add(it.id) }
                views.forEach { add(it.id) }
            }
        var n = notes.size
        while ("note_$n" in used) n++
        return "note_$n"
    }

    private fun autoId(
        prefix: String,
        n: Int,
    ) = "${prefix}_$n"

    fun build(): ErmModel {
        val finalDiagrams =
            diagrams.ifEmpty {
                listOf(ErmDiagram(name = name, notation = ErmNotation.MARTIN))
            }
        val model =
            ErmModel(
                name = name,
                entities = entities.toList(),
                relationships = relationships.toList(),
                views = views.toList(),
                diagrams = finalDiagrams,
                categories = categories.toList(),
                notes = notes.toList(),
                noteLinks = noteLinks.toList(),
            )
        val seenNoteIds = mutableSetOf<String>()
        val otherIds = (model.entities.map { it.id } + model.views.map { it.id }).toSet()
        for (n in model.notes) {
            require(seenNoteIds.add(n.id)) { "duplicate note id '${n.id}'" }
            require(n.id !in otherIds) { "note id '${n.id}' collides with an entity or view id" }
        }
        val anchorable = model.anchorableIds()
        for (l in model.noteLinks) {
            require(l.link.targetId in anchorable) {
                "note '${l.link.noteId}' cannot be anchored to '${l.link.targetId}': unknown id, or neither an entity nor a view"
            }
        }
        return model
    }
}

/**
 * DSL entry point — analogous to `blueprint(name) { }`, `bpmnModel(name) { }`.
 */
fun ermModel(
    name: String,
    block: ErmModelBuilder.() -> Unit,
): ErmModel = ErmModelBuilder(name).apply(block).build()
