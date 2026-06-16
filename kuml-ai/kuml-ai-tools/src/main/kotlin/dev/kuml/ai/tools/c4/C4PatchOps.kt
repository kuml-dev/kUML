package dev.kuml.ai.tools.c4

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem

/**
 * Pure mutation functions for C4 model editing.
 */
internal object C4PatchOps {
    internal fun addPerson(
        model: AnyKumlModel.C4,
        id: String,
        name: String,
        description: String?,
    ): AnyKumlModel.C4 {
        val person = C4Person(id = id, name = name, description = description)
        return model.copy(model = model.model.addElement(person))
    }

    internal fun addSoftwareSystem(
        model: AnyKumlModel.C4,
        id: String,
        name: String,
        description: String?,
        isExternal: Boolean,
    ): AnyKumlModel.C4 {
        val system = C4SoftwareSystem(id = id, name = name, description = description, external = isExternal)
        return model.copy(model = model.model.addElement(system))
    }

    internal fun addContainer(
        model: AnyKumlModel.C4,
        id: String,
        systemId: String,
        name: String,
        technology: String?,
        description: String?,
    ): AnyKumlModel.C4 {
        val container = C4Container(id = id, name = name, description = description, technology = technology, system = systemId)
        // Register container id in the parent system
        val newElements =
            model.model.elements.map { el ->
                if (el.id == systemId && el is C4SoftwareSystem) {
                    el.copy(containers = el.containers + id)
                } else {
                    el
                }
            } + container
        return model.copy(model = model.model.copy(elements = newElements))
    }

    internal fun addComponent(
        model: AnyKumlModel.C4,
        id: String,
        containerId: String,
        name: String,
        technology: String?,
        description: String?,
    ): AnyKumlModel.C4 {
        val component = C4Component(id = id, name = name, description = description, technology = technology, container = containerId)
        // Register component id in the parent container
        val newElements =
            model.model.elements.map { el ->
                if (el.id == containerId && el is C4Container) {
                    el.copy(components = el.components + id)
                } else {
                    el
                }
            } + component
        return model.copy(model = model.model.copy(elements = newElements))
    }

    internal fun addRelationship(
        model: AnyKumlModel.C4,
        id: String,
        sourceId: String,
        targetId: String,
        label: String,
        technology: String?,
    ): AnyKumlModel.C4 {
        val rel = C4Relationship(id = id, source = sourceId, target = targetId, label = label, technology = technology)
        return model.copy(model = model.model.copy(relationships = model.model.relationships + rel))
    }

    internal fun resolveElement(
        model: AnyKumlModel.C4,
        idOrName: String,
    ): C4Element? =
        model.model.elements.firstOrNull { it.id == idOrName }
            ?: model.model.elements.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }

    internal fun allIds(model: AnyKumlModel.C4): Set<String> =
        (model.model.elements.map { it.id } + model.model.relationships.map { it.id }).toSet()

    private fun C4Model.addElement(el: C4Element): C4Model = copy(elements = elements + el)
}
