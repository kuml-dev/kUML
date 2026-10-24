package dev.kuml.transform.umlerm

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import java.util.IdentityHashMap

/**
 * One input of [UmlModelMerger.merge].
 *
 * @property origin Stable, unique key of the source (for example the invariant relative path of the
 *   script). It defines the deterministic processing order and appears in conflict messages.
 * @property diagram The UML class diagram evaluated from that source.
 */
public data class UmlModelSource(
    val origin: String,
    val diagram: KumlDiagram,
)

/**
 * Thrown by [UmlModelMerger.merge] when two declarations of "the same" entity (same
 * `«Entity».tableName`) cannot be reconciled: the non-canonical declaration has attributes the
 * canonical one lacks. Extends [IllegalStateException] so callers catching that type keep working.
 */
public class UmlModelMergeException(
    public val tableName: String,
    public val canonicalClassName: String,
    public val canonicalOrigin: String,
    public val conflictingClassName: String,
    public val conflictingOrigin: String,
    public val missingAttributes: List<String>,
    message: String,
) : IllegalStateException(message)

/**
 * Merges several UML class diagrams (typically one per `.kuml.kts` script) into one model,
 * de-duplicating cross-script "stub" entities.
 *
 * ## Why
 *
 * A script that needs a foreign entity as association target has to declare at least an id-only
 * stub of it. Class ids are derived from the class name, so stubs and full declarations usually
 * share an id across scripts. Concatenating the scripts would produce duplicate, colliding
 * declarations of the same table downstream.
 *
 * ## Algorithm
 *
 * 1. Sort the sources by [UmlModelSource.origin]; everything below follows this order, so the result
 *    does not depend on the order of the input list.
 * 2. Union all elements.
 * 3. Group [UmlClass] elements carrying an `«Entity»` stereotype (ERM profile namespace) with a
 *    non-blank `tableName` tag by that tag value. Other classes pass through unchanged.
 * 4. Per group the canonical declaration is the one with the most attributes; ties go to the first
 *    encountered one (smallest origin, then declaration order).
 * 5. Every other declaration's attribute names must be a subset of the canonical's, otherwise a
 *    [UmlModelMergeException] is thrown.
 * 6. Dropped ids map to the canonical id; association ends, generalizations, interface realizations
 *    and dependencies are rewritten through that map. Canonical-vs-duplicate bookkeeping uses
 *    reference identity (`===`), never id equality, because ids are usually equal across scripts.
 * 7. Associations with the same signature after remapping (the sorted list of `(typeId, role)` pairs
 *    of their ends, `null` role treated as empty) are de-duplicated; the first wins. If the
 *    duplicates stem from different sources and differ in name, applied stereotypes, multiplicity,
 *    navigability or aggregation,
 *    a [UmlModelMergeException] is thrown. Equal-signature associations of one source that differ in
 *    these details are distinct declarations and both stay.
 * 8. Enumerations with the same name are de-duplicated like entities: the declaration with the most
 *    literals is canonical, every other declaration's literal names must be a subset of it,
 *    otherwise a [UmlModelMergeException] is thrown (silently dropping literals would change
 *    generated code).
 * 9. Plain classes (no `«Entity»` table name) and interfaces are de-duplicated by name, per kind,
 *    across different sources only: the declaration with the most members (attributes plus
 *    operations) is canonical, every declaration from another source must have a subset of its
 *    member names, otherwise a [UmlModelMergeException] is thrown. Duplicates inside one source are
 *    left alone. Without this, generators that write one file per classifier would emit the same
 *    file twice and the later stub would overwrite the full declaration.
 * 10. A plain class from one source that shares its name with an `«Entity»` of another source (a
 *    stereotype-less stub) is merged into the entity if its member names are a subset, otherwise a
 *    [UmlModelMergeException] is thrown.
 * 11. Generalizations, interface realizations and dependencies that are equal after remapping (same
 *    ends; dependencies also same name and stereotypes) are de-duplicated across sources; the first
 *    wins. Repeats within one source stay.
 *
 * ## Output order
 *
 * All non-association elements of every source in sorted source order (minus non-canonical
 * duplicates; the canonical declaration keeps its own position), followed by all de-duplicated
 * associations in sorted source order. Metadata and config of the result are empty.
 *
 * A single source is returned unchanged (the same instance).
 */
public object UmlModelMerger {
    /** Name of the merged diagram when none is given. */
    public const val DEFAULT_MERGED_NAME: String = "merged"

    /**
     * Merges [sources] into one diagram named [mergedName].
     *
     * @throws IllegalArgumentException for an empty list, duplicate origins or non-class diagrams.
     * @throws UmlModelMergeException for irreconcilable duplicate entity declarations.
     */
    public fun merge(
        sources: List<UmlModelSource>,
        mergedName: String = DEFAULT_MERGED_NAME,
    ): KumlDiagram {
        require(sources.isNotEmpty()) { "At least one source is required to merge." }
        val duplicateOrigin =
            sources
                .groupingBy { it.origin }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
                ?.key
        require(duplicateOrigin == null) { "Duplicate source origin '$duplicateOrigin'." }
        sources.forEach { source ->
            require(source.diagram.type == DiagramType.CLASS) {
                "Source '${source.origin}' is a ${source.diagram.type} diagram; only CLASS diagrams can be merged."
            }
        }
        if (sources.size == 1) return sources.single().diagram

        val ordered = sources.sortedBy { it.origin }
        val allElements = ArrayList<KumlElement>()
        val originOf = IdentityHashMap<KumlElement, String>()
        for (source in ordered) {
            for (element in source.diagram.elements) {
                allElements += element
                originOf[element] = source.origin
            }
        }

        val entityClassesByTable: Map<String, List<UmlClass>> =
            allElements
                .filterIsInstance<UmlClass>()
                .mapNotNull { cls -> cls.entityTableName()?.let { it to cls } }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        val idRemap = HashMap<String, String>()
        val canonicalByTable = HashMap<String, UmlClass>()

        for ((tableName, declarations) in entityClassesByTable) {
            val canonical =
                declarations.reduce { winner, candidate ->
                    if (candidate.attributes.size > winner.attributes.size) candidate else winner
                }
            canonicalByTable[tableName] = canonical
            if (declarations.size <= 1) continue

            val canonicalAttributeNames = canonical.attributes.map { it.name }.toSet()
            for (duplicate in declarations) {
                if (duplicate === canonical) continue
                val missing = duplicate.attributes.map { it.name }.filterNot { it in canonicalAttributeNames }
                if (missing.isNotEmpty()) {
                    val canonicalOrigin = originOf[canonical].orEmpty()
                    val conflictingOrigin = originOf[duplicate].orEmpty()
                    throw UmlModelMergeException(
                        tableName = tableName,
                        canonicalClassName = canonical.name,
                        canonicalOrigin = canonicalOrigin,
                        conflictingClassName = duplicate.name,
                        conflictingOrigin = conflictingOrigin,
                        missingAttributes = missing,
                        message =
                            "Entity merge conflict for table '$tableName': declaration '${duplicate.name}' " +
                                "in '$conflictingOrigin' has attribute(s) ${missing.joinToString(prefix = "[", postfix = "]")} " +
                                "that the canonical declaration '${canonical.name}' in '$canonicalOrigin' " +
                                "(attributes=${canonical.attributes.map { it.name }}) does not have. " +
                                "Resolve this modeling conflict by hand; it is not merged silently.",
                    )
                }
                idRemap[duplicate.id] = canonical.id
            }
        }

        val enumsByName: Map<String, List<UmlEnumeration>> =
            allElements.filterIsInstance<UmlEnumeration>().groupBy { it.name }
        val canonicalEnumByName = HashMap<String, UmlEnumeration>()
        for ((enumName, declarations) in enumsByName) {
            val canonical =
                declarations.reduce { winner, candidate ->
                    if (candidate.literals.size > winner.literals.size) candidate else winner
                }
            canonicalEnumByName[enumName] = canonical
            if (declarations.size <= 1) continue
            val canonicalLiterals = canonical.literals.map { it.name }.toSet()
            for (duplicate in declarations) {
                if (duplicate === canonical) continue
                val missing = duplicate.literals.map { it.name }.filterNot { it in canonicalLiterals }
                if (missing.isNotEmpty()) {
                    val canonicalOrigin = originOf[canonical].orEmpty()
                    val conflictingOrigin = originOf[duplicate].orEmpty()
                    throw UmlModelMergeException(
                        tableName = enumName,
                        canonicalClassName = canonical.name,
                        canonicalOrigin = canonicalOrigin,
                        conflictingClassName = duplicate.name,
                        conflictingOrigin = conflictingOrigin,
                        missingAttributes = missing,
                        message =
                            "Enumeration merge conflict for '$enumName': declaration in '$conflictingOrigin' " +
                                "has literal(s) ${missing.joinToString(prefix = "[", postfix = "]")} " +
                                "that the canonical declaration in '$canonicalOrigin' " +
                                "(literals=${canonical.literals.map { it.name }}) does not have. " +
                                "Resolve this modeling conflict by hand; it is not merged silently.",
                    )
                }
                idRemap[duplicate.id] = canonical.id
            }
        }

        val droppedClassifiers = IdentityHashMap<KumlElement, Boolean>()
        val allPlainClasses =
            allElements.filterIsInstance<UmlClass>().filter { it.entityTableName() == null }
        dedupPlainAgainstEntities(
            plainClasses = allPlainClasses,
            canonicalEntities = allElements.filterIsInstance<UmlClass>().filter { it === canonicalByTable[it.entityTableName()] },
            originOf = originOf,
            idRemap = idRemap,
            dropped = droppedClassifiers,
        )
        val plainClasses = allPlainClasses.filterNot { droppedClassifiers.containsKey(it) }
        dedupByName(
            kind = "Class",
            declarations = plainClasses,
            nameOf = { it.name },
            membersOf = { cls -> cls.attributes.map { it.name } + cls.operations.map { it.name } },
            idOf = { it.id },
            originOf = originOf,
            idRemap = idRemap,
            dropped = droppedClassifiers,
        )
        dedupByName(
            kind = "Interface",
            declarations = allElements.filterIsInstance<UmlInterface>(),
            nameOf = { it.name },
            membersOf = { itf -> itf.attributes.map { it.name } + itf.operations.map { it.name } },
            idOf = { it.id },
            originOf = originOf,
            idRemap = idRemap,
            dropped = droppedClassifiers,
        )

        val dedupedAssociations = dedupAssociations(allElements = allElements, originOf = originOf, idRemap = idRemap)

        val seenRelationships = HashMap<List<Any?>, String>()
        val mergedElements =
            allElements.mapNotNull { element ->
                when (element) {
                    is UmlClass -> {
                        val tableName = element.entityTableName()
                        if (tableName == null) {
                            if (droppedClassifiers.containsKey(element)) null else element
                        } else if (element === canonicalByTable[tableName]) {
                            element
                        } else {
                            null
                        }
                    }
                    is UmlInterface -> if (droppedClassifiers.containsKey(element)) null else element
                    is UmlEnumeration -> if (element === canonicalEnumByName[element.name]) element else null
                    is UmlAssociation -> null
                    is UmlGeneralization ->
                        dedupRelationship(
                            relationship =
                                element.copy(
                                    specificId = idRemap[element.specificId] ?: element.specificId,
                                    generalId = idRemap[element.generalId] ?: element.generalId,
                                ),
                            key =
                                listOf(
                                    "gen",
                                    idRemap[element.specificId] ?: element.specificId,
                                    idRemap[element.generalId] ?: element.generalId,
                                ),
                            origin = originOf[element].orEmpty(),
                            seenRelationships = seenRelationships,
                        )
                    is UmlInterfaceRealization ->
                        dedupRelationship(
                            relationship =
                                element.copy(
                                    implementingId = idRemap[element.implementingId] ?: element.implementingId,
                                    interfaceId = idRemap[element.interfaceId] ?: element.interfaceId,
                                ),
                            key =
                                listOf(
                                    "real",
                                    idRemap[element.implementingId] ?: element.implementingId,
                                    idRemap[element.interfaceId] ?: element.interfaceId,
                                ),
                            origin = originOf[element].orEmpty(),
                            seenRelationships = seenRelationships,
                        )
                    is UmlDependency ->
                        dedupRelationship(
                            relationship =
                                element.copy(
                                    clientId = idRemap[element.clientId] ?: element.clientId,
                                    supplierId = idRemap[element.supplierId] ?: element.supplierId,
                                ),
                            key =
                                listOf(
                                    "dep",
                                    idRemap[element.clientId] ?: element.clientId,
                                    idRemap[element.supplierId] ?: element.supplierId,
                                    element.name,
                                    element.appliedStereotypes.map { it.toString() }.sorted(),
                                ),
                            origin = originOf[element].orEmpty(),
                            seenRelationships = seenRelationships,
                        )
                    else -> element
                }
            } + dedupedAssociations

        return KumlDiagram(name = mergedName, type = DiagramType.CLASS, elements = mergedElements)
    }

    private fun UmlClass.entityTableName(): String? =
        appliedStereotypes
            .ermStereotype(ErmProfileNames.ENTITY)
            ?.stringTag(ErmProfileNames.TAG_TABLE_NAME)
            ?.takeIf { it.isNotBlank() }

    /**
     * Cross-source de-duplication of same-named classifiers. Marks non-canonical declarations in
     * [dropped] and records their id in [idRemap]. Declarations sharing a source with the canonical
     * one are kept untouched.
     */
    private fun <T : KumlElement> dedupByName(
        kind: String,
        declarations: List<T>,
        nameOf: (T) -> String,
        membersOf: (T) -> List<String>,
        idOf: (T) -> String,
        originOf: IdentityHashMap<KumlElement, String>,
        idRemap: MutableMap<String, String>,
        dropped: IdentityHashMap<KumlElement, Boolean>,
    ) {
        for ((name, group) in declarations.groupBy(nameOf)) {
            if (group.map { originOf[it].orEmpty() }.distinct().size <= 1) continue
            val canonical =
                group.reduce { winner, candidate ->
                    if (membersOf(candidate).size > membersOf(winner).size) candidate else winner
                }
            val canonicalOrigin = originOf[canonical].orEmpty()
            val canonicalMembers = membersOf(canonical).toSet()
            for (duplicate in group) {
                val duplicateOrigin = originOf[duplicate].orEmpty()
                if (duplicate === canonical || duplicateOrigin == canonicalOrigin) continue
                val missing = membersOf(duplicate).filterNot { it in canonicalMembers }
                if (missing.isNotEmpty()) {
                    throw UmlModelMergeException(
                        tableName = name,
                        canonicalClassName = name,
                        canonicalOrigin = canonicalOrigin,
                        conflictingClassName = name,
                        conflictingOrigin = duplicateOrigin,
                        missingAttributes = missing,
                        message =
                            "$kind merge conflict for '$name': declaration in '$duplicateOrigin' " +
                                "has member(s) ${missing.joinToString(prefix = "[", postfix = "]")} " +
                                "that the canonical declaration in '$canonicalOrigin' " +
                                "(members=${membersOf(canonical)}) does not have. " +
                                "Resolve this modeling conflict by hand; it is not merged silently.",
                    )
                }
                dropped[duplicate] = true
                idRemap[idOf(duplicate)] = idOf(canonical)
            }
        }
    }

    /**
     * A plain class (no `«Entity»` table name) from one source that shares its name with an entity of
     * another source is a stereotype-less stub of that entity: dropped and remapped to the entity when
     * its member names are a subset of the entity's, otherwise a [UmlModelMergeException].
     */
    private fun dedupPlainAgainstEntities(
        plainClasses: List<UmlClass>,
        canonicalEntities: List<UmlClass>,
        originOf: IdentityHashMap<KumlElement, String>,
        idRemap: MutableMap<String, String>,
        dropped: IdentityHashMap<KumlElement, Boolean>,
    ) {
        fun UmlClass.members(): List<String> = attributes.map { it.name } + operations.map { it.name }
        for (plain in plainClasses) {
            val plainOrigin = originOf[plain].orEmpty()
            val entity =
                canonicalEntities.firstOrNull { it.name == plain.name && originOf[it].orEmpty() != plainOrigin }
                    ?: continue
            val entityMembers = entity.members().toSet()
            val missing = plain.members().filterNot { it in entityMembers }
            val entityOrigin = originOf[entity].orEmpty()
            if (missing.isNotEmpty()) {
                throw UmlModelMergeException(
                    tableName = entity.entityTableName().orEmpty(),
                    canonicalClassName = entity.name,
                    canonicalOrigin = entityOrigin,
                    conflictingClassName = plain.name,
                    conflictingOrigin = plainOrigin,
                    missingAttributes = missing,
                    message =
                        "Class merge conflict for '${plain.name}': declaration without «Entity» in '$plainOrigin' " +
                            "has member(s) ${missing.joinToString(prefix = "[", postfix = "]")} " +
                            "that the «Entity» declaration in '$entityOrigin' " +
                            "(members=${entity.members()}) does not have. " +
                            "Resolve this modeling conflict by hand; it is not merged silently.",
                )
            }
            dropped[plain] = true
            idRemap[plain.id] = entity.id
        }
    }

    /**
     * Returns [relationship] unless an equal one (same [key] after id remapping) was already kept from
     * a different source, in which case it is dropped (`null`). Repeats within one source stay.
     */
    private fun <T : KumlElement> dedupRelationship(
        relationship: T,
        key: List<Any?>,
        origin: String,
        seenRelationships: MutableMap<List<Any?>, String>,
    ): T? {
        val firstOrigin = seenRelationships.getOrPut(key) { origin }
        return if (firstOrigin == origin) relationship else null
    }

    private data class AssociationSignature(
        val ends: List<Pair<String, String>>,
    )

    private fun UmlAssociation.dedupSignature(): AssociationSignature =
        AssociationSignature(
            ends =
                ends
                    .map { it.typeId to (it.role ?: "") }
                    .sortedWith(compareBy({ it.first }, { it.second })),
        )

    /** Everything besides the signature that distinguishes two declarations of the same association. */
    private fun UmlAssociation.detailKey(): List<Any?> =
        listOf(name, aggregation, appliedStereotypes.map { it.toString() }.sorted()) +
            ends
                .sortedWith(compareBy({ it.typeId }, { it.role ?: "" }))
                .map { listOf(it.multiplicity, it.navigable) }

    private fun dedupAssociations(
        allElements: List<KumlElement>,
        originOf: IdentityHashMap<KumlElement, String>,
        idRemap: Map<String, String>,
    ): List<UmlAssociation> {
        class Seen(
            val origin: String,
            val details: MutableSet<List<Any?>>,
        )
        val seen = HashMap<AssociationSignature, MutableList<Seen>>()
        val result = ArrayList<UmlAssociation>()
        for (original in allElements.filterIsInstance<UmlAssociation>()) {
            val assoc = original.copy(ends = original.ends.map { end -> end.copy(typeId = idRemap[end.typeId] ?: end.typeId) })
            val origin = originOf[original].orEmpty()
            val signature = assoc.dedupSignature()
            val details = assoc.detailKey()
            val previous = seen.getOrPut(signature) { ArrayList() }
            if (previous.isEmpty()) {
                previous += Seen(origin, mutableSetOf(details))
                result += assoc
                continue
            }
            // Same source: identical details collapse, differing details are distinct declarations.
            val sameOrigin = previous.firstOrNull { it.origin == origin }
            if (sameOrigin != null) {
                if (sameOrigin.details.add(details)) result += assoc
                continue
            }
            val other = previous.first()
            if (previous.none { details in it.details }) {
                throw UmlModelMergeException(
                    tableName = signature.ends.joinToString(" - ") { (type, role) -> if (role.isEmpty()) type else "$type.$role" },
                    canonicalClassName = other.origin,
                    canonicalOrigin = other.origin,
                    conflictingClassName = origin,
                    conflictingOrigin = origin,
                    missingAttributes = emptyList(),
                    message =
                        "Association merge conflict between '${other.origin}' and '$origin': both declare an " +
                            "association over ${signature.ends} but differ in name, applied stereotypes, multiplicity, " +
                            "navigability or aggregation. Resolve this modeling conflict by hand; it is not merged silently.",
                )
            }
            previous += Seen(origin, mutableSetOf(details))
        }
        return result
    }
}
