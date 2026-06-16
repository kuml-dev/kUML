package dev.kuml.ai.tools.uml

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.internal.IdHelpers
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility

/**
 * Pure functions that build new AnyKumlModel.Uml instances from mutations.
 *
 * These functions do not interact with Koog or coroutines — they are called by
 * UmlEditingTools via AgentEditingContext.applyPatch(patch) { mutate(it) }.
 */
internal object UmlPatchOps {
    internal fun addClass(
        model: AnyKumlModel.Uml,
        id: String,
        name: String,
        stereotype: String?,
        isAbstract: Boolean,
        attributes: List<UmlProperty> = emptyList(),
    ): AnyKumlModel.Uml {
        val cls =
            UmlClass(
                id = id,
                name = name,
                isAbstract = isAbstract,
                stereotypes = if (stereotype != null) listOf(stereotype) else emptyList(),
                attributes = attributes,
            )
        return model.copy(elements = model.elements + cls)
    }

    internal fun addInterface(
        model: AnyKumlModel.Uml,
        id: String,
        name: String,
        operations: List<UmlOperation> = emptyList(),
    ): AnyKumlModel.Uml {
        val iface =
            UmlInterface(
                id = id,
                name = name,
                operations = operations,
            )
        return model.copy(elements = model.elements + iface)
    }

    /**
     * Adds an attribute to the classifier identified by [classifierId].
     * Returns null if the classifier is not found.
     */
    internal fun addAttribute(
        model: AnyKumlModel.Uml,
        classifierId: String,
        attrId: String,
        attrName: String,
        typeName: String,
        visibility: Visibility,
        defaultValue: String?,
    ): AnyKumlModel.Uml? {
        val property =
            UmlProperty(
                id = attrId,
                name = attrName,
                type = UmlTypeRef(typeName),
                visibility = visibility,
                defaultValue = defaultValue,
            )
        val newElements =
            model.elements.map { el ->
                when {
                    el.id == classifierId && el is UmlClass ->
                        el.copy(attributes = el.attributes + property)
                    el.id == classifierId && el is UmlInterface ->
                        el.copy(attributes = el.attributes + property)
                    else -> el
                }
            }
        return if (newElements == model.elements) null else model.copy(elements = newElements)
    }

    /**
     * Adds an operation to the classifier identified by [classifierId].
     * Returns null if the classifier is not found.
     */
    internal fun addOperation(
        model: AnyKumlModel.Uml,
        classifierId: String,
        opId: String,
        opName: String,
        parameters: List<UmlParameter>,
        returnType: UmlTypeRef?,
        visibility: Visibility,
    ): AnyKumlModel.Uml? {
        val operation =
            UmlOperation(
                id = opId,
                name = opName,
                parameters = parameters,
                returnType = returnType,
                visibility = visibility,
            )
        val newElements =
            model.elements.map { el ->
                when {
                    el.id == classifierId && el is UmlClass ->
                        el.copy(operations = el.operations + operation)
                    el.id == classifierId && el is UmlInterface ->
                        el.copy(operations = el.operations + operation)
                    else -> el
                }
            }
        return if (newElements == model.elements) null else model.copy(elements = newElements)
    }

    internal fun addAssociation(
        model: AnyKumlModel.Uml,
        assocId: String,
        sourceId: String,
        targetId: String,
        name: String?,
        sourceMultiplicity: Multiplicity,
        targetMultiplicity: Multiplicity,
    ): AnyKumlModel.Uml {
        val assoc =
            UmlAssociation(
                id = assocId,
                name = name,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = sourceId, multiplicity = sourceMultiplicity),
                        UmlAssociationEnd(typeId = targetId, multiplicity = targetMultiplicity),
                    ),
            )
        return model.copy(relationships = model.relationships + assoc)
    }

    internal fun addGeneralization(
        model: AnyKumlModel.Uml,
        genId: String,
        childId: String,
        parentId: String,
    ): AnyKumlModel.Uml {
        val gen = UmlGeneralization(id = genId, specificId = childId, generalId = parentId)
        return model.copy(relationships = model.relationships + gen)
    }

    /**
     * Removes an element (named element or relationship) by id.
     * Also removes dangling relationships that referenced the removed element.
     * Returns null if the element is not found.
     */
    internal fun removeElement(
        model: AnyKumlModel.Uml,
        elementId: String,
    ): AnyKumlModel.Uml? {
        val removedFromElements = model.elements.none { it.id == elementId }
        val removedFromRelationships = model.relationships.none { it.id == elementId }
        if (removedFromElements && removedFromRelationships) return null

        val newElements = model.elements.filter { it.id != elementId }
        // Cascade: remove relationships that reference the deleted element
        val newRelationships =
            model.relationships.filter { rel ->
                rel.id != elementId &&
                    when (rel) {
                        is UmlAssociation -> rel.ends.none { it.typeId == elementId }
                        is UmlGeneralization -> rel.specificId != elementId && rel.generalId != elementId
                        else -> true
                    }
            }
        return model.copy(elements = newElements, relationships = newRelationships)
    }

    /**
     * Renames an element. Returns null if not found.
     */
    internal fun renameElement(
        model: AnyKumlModel.Uml,
        elementId: String,
        newName: String,
    ): Pair<AnyKumlModel.Uml, String>? {
        var oldName: String? = null
        val newElements =
            model.elements.map { el ->
                if (el.id == elementId) {
                    oldName = el.name
                    when (el) {
                        is UmlClass -> el.copy(name = newName)
                        is UmlInterface -> el.copy(name = newName)
                        else -> el
                    }
                } else {
                    el
                }
            }
        return if (oldName == null) null else (model.copy(elements = newElements) to oldName)
    }

    /** Resolves a classifier by id or by name (case-insensitive). */
    internal fun resolveClassifier(
        model: AnyKumlModel.Uml,
        idOrName: String,
    ): UmlNamedElement? =
        model.elements.firstOrNull { it.id == idOrName }
            ?: model.elements.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }

    /** Parses a multiplicity string like "1", "0..*", "1..*" to a Multiplicity. */
    internal fun parseMultiplicity(s: String?): Multiplicity {
        if (s.isNullOrBlank()) return Multiplicity()
        return when (s.trim()) {
            "1" -> Multiplicity(1, 1)
            "*", "0..*" -> Multiplicity(0, null)
            "1..*" -> Multiplicity(1, null)
            "0..1" -> Multiplicity(0, 1)
            else -> {
                if (s.contains("..")) {
                    val parts = s.split("..")
                    val lower = parts[0].trim().toIntOrNull() ?: 1
                    val upper = parts[1].trim().let { if (it == "*") null else it.toIntOrNull() }
                    Multiplicity(lower, upper)
                } else {
                    val n = s.trim().toIntOrNull() ?: 1
                    Multiplicity(n, n)
                }
            }
        }
    }

    /** Parses "name: Type" parameter strings into UmlParameter list. */
    internal fun parseParameters(
        params: List<String>,
        takenIds: Set<String>,
    ): List<UmlParameter> {
        val taken = takenIds.toMutableSet()
        return params.mapNotNull { spec ->
            val trimmed = spec.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val colon = trimmed.indexOf(':')
            if (colon < 0) {
                // No type annotation — use String as default
                val id = IdHelpers.uniqueId(trimmed, taken, "param")
                taken += id
                UmlParameter(id = id, name = trimmed, type = UmlTypeRef("Any"))
            } else {
                val paramName = trimmed.substring(0, colon).trim()
                val typeName = trimmed.substring(colon + 1).trim()
                val id = IdHelpers.uniqueId(paramName, taken, "param")
                taken += id
                UmlParameter(id = id, name = paramName, type = UmlTypeRef(typeName))
            }
        }
    }
}
