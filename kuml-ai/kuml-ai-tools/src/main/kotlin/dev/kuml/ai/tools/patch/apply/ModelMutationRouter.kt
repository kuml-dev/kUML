package dev.kuml.ai.tools.patch.apply

// Reconstructs the mutate-function for a ModelPatch — needed by PatchValidator
// which must apply the patch on a CLONE before structural checks run.
//
// V3.0.25: covers the 5 ModelPatch sub-types. ModelMutationRouter is internal
// because V3.0.24 must NOT call it directly — all external callers go through
// PatchApplyEngine.applyOne() which holds the correct mutex-ordering guarantee.

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.c4.model.C4Relationship
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Maps a [ModelPatch] to a mutation function `(AnyKumlModel) -> AnyKumlModel`.
 *
 * The reconstruction is necessarily incomplete for complex `AddElement` patches (e.g.
 * a class with many attributes). For validation purposes the reconstructed model
 * needs to be structurally valid — the precise shape of added elements is secondary.
 * Structural checks (DUPLICATE_ID, CIRCULAR_INHERITANCE, DANGLING_REFERENCE) operate
 * on IDs and relationship topology, which are fully captured in [ModelPatch].
 */
internal object ModelMutationRouter {
    /**
     * Returns a mutation function for [patch] or throws [IllegalArgumentException]
     * if the patch type is unrecognised.
     */
    internal fun mutateFor(patch: ModelPatch): (AnyKumlModel) -> AnyKumlModel =
        when (patch) {
            is ModelPatch.AddElement -> addElementMutate(patch)
            is ModelPatch.RemoveElement -> removeElementMutate(patch)
            is ModelPatch.UpdateAttribute -> updateAttributeMutate(patch)
            is ModelPatch.RenameElement -> renameElementMutate(patch)
            is ModelPatch.AddRelationship -> addRelationshipMutate(patch)
        }

    // ── AddElement ─────────────────────────────────────────────────────────────

    private fun addElementMutate(patch: ModelPatch.AddElement): (AnyKumlModel) -> AnyKumlModel =
        { model ->
            when (model) {
                is AnyKumlModel.Uml -> addUmlElement(model, patch)
                is AnyKumlModel.C4 -> addC4Element(model, patch)
                is AnyKumlModel.Sysml2 -> addSysml2Element(model, patch)
            }
        }

    private fun addUmlElement(
        model: AnyKumlModel.Uml,
        patch: ModelPatch.AddElement,
    ): AnyKumlModel.Uml {
        val newElement: UmlNamedElement =
            when {
                patch.elementKind.endsWith("interface") ->
                    UmlInterface(id = patch.elementId, name = patch.name)
                else ->
                    // Default: create a plain UmlClass for any uml.* kind not explicitly matched
                    UmlClass(
                        id = patch.elementId,
                        name = patch.name,
                        isAbstract = patch.payload["isAbstract"] == "true",
                        stereotypes = listOfNotNull(patch.payload["stereotype"]),
                    )
            }
        return model.copy(elements = model.elements + newElement)
    }

    private fun addC4Element(
        model: AnyKumlModel.C4,
        patch: ModelPatch.AddElement,
    ): AnyKumlModel.C4 {
        // Minimal C4 element stub — enough for structural ID checks
        val newElement =
            dev.kuml.c4.model.C4Container(
                id = patch.elementId,
                name = patch.name,
                description = patch.payload["description"],
                technology = patch.payload["technology"],
            )
        return model.copy(model = model.model.copy(elements = model.model.elements + newElement))
    }

    private fun addSysml2Element(
        model: AnyKumlModel.Sysml2,
        patch: ModelPatch.AddElement,
    ): AnyKumlModel.Sysml2 {
        val newDef =
            dev.kuml.sysml2.PartDefinition(
                id = patch.elementId,
                name = patch.name,
            )
        return model.copy(model = model.model.copy(definitions = model.model.definitions + newDef))
    }

    // ── RemoveElement ──────────────────────────────────────────────────────────

    private fun removeElementMutate(patch: ModelPatch.RemoveElement): (AnyKumlModel) -> AnyKumlModel =
        { model ->
            when (model) {
                is AnyKumlModel.Uml -> {
                    val newElements = model.elements.filter { it.id != patch.elementId }
                    val newRels =
                        model.relationships.filter { rel ->
                            rel.id != patch.elementId &&
                                when (rel) {
                                    is UmlAssociation -> rel.ends.none { it.typeId == patch.elementId }
                                    is UmlGeneralization -> rel.specificId != patch.elementId && rel.generalId != patch.elementId
                                    else -> true
                                }
                        }
                    model.copy(elements = newElements, relationships = newRels)
                }
                is AnyKumlModel.C4 -> {
                    val newElements = model.model.elements.filter { it.id != patch.elementId }
                    val newRels = model.model.relationships.filter { it.id != patch.elementId }
                    model.copy(model = model.model.copy(elements = newElements, relationships = newRels))
                }
                is AnyKumlModel.Sysml2 -> {
                    val newDefs = model.model.definitions.filter { it.id != patch.elementId }
                    val newUsages = model.model.usages.filter { it.id != patch.elementId }
                    model.copy(model = model.model.copy(definitions = newDefs, usages = newUsages))
                }
            }
        }

    // ── UpdateAttribute ────────────────────────────────────────────────────────

    /**
     * UpdateAttribute patches are used for guard/effect/entry/exit/doActivity fields
     * on state-machine elements. For structural validation purposes we simply return
     * the model unchanged — the structural checks do not inspect expression content.
     * The SANDBOX and TYPE_CHECK phases handle expression validation.
     */
    private fun updateAttributeMutate(patch: ModelPatch.UpdateAttribute): (AnyKumlModel) -> AnyKumlModel =
        { model ->
            // Identity — UpdateAttribute does not change structural topology
            model
        }

    // ── RenameElement ──────────────────────────────────────────────────────────

    private fun renameElementMutate(patch: ModelPatch.RenameElement): (AnyKumlModel) -> AnyKumlModel =
        { model ->
            when (model) {
                is AnyKumlModel.Uml -> {
                    val newElements =
                        model.elements.map { el ->
                            if (el.id != patch.elementId) return@map el
                            when (el) {
                                is UmlClass -> el.copy(name = patch.newName)
                                is UmlInterface -> el.copy(name = patch.newName)
                                else -> el
                            }
                        }
                    model.copy(elements = newElements)
                }
                is AnyKumlModel.C4 -> model // C4 rename: identity for structural checks
                is AnyKumlModel.Sysml2 -> model // SysML2 rename: identity for structural checks
            }
        }

    // ── AddRelationship ────────────────────────────────────────────────────────

    private fun addRelationshipMutate(patch: ModelPatch.AddRelationship): (AnyKumlModel) -> AnyKumlModel =
        { model ->
            when (model) {
                is AnyKumlModel.Uml -> addUmlRelationship(model, patch)
                is AnyKumlModel.C4 -> addC4Relationship(model, patch)
                is AnyKumlModel.Sysml2 -> model // SysML2 relationships: identity for structural checks
            }
        }

    private fun addUmlRelationship(
        model: AnyKumlModel.Uml,
        patch: ModelPatch.AddRelationship,
    ): AnyKumlModel.Uml {
        val newRel: UmlRelationship =
            when {
                patch.relationshipKind.contains("generalization", ignoreCase = true) ->
                    UmlGeneralization(
                        id = patch.relationshipId,
                        specificId = patch.sourceId,
                        generalId = patch.targetId,
                    )
                patch.relationshipKind.contains("dependency", ignoreCase = true) ->
                    UmlDependency(
                        id = patch.relationshipId,
                        clientId = patch.sourceId,
                        supplierId = patch.targetId,
                    )
                else ->
                    UmlAssociation(
                        id = patch.relationshipId,
                        ends =
                            listOf(
                                UmlAssociationEnd(typeId = patch.sourceId),
                                UmlAssociationEnd(typeId = patch.targetId),
                            ),
                    )
            }
        return model.copy(relationships = model.relationships + newRel)
    }

    private fun addC4Relationship(
        model: AnyKumlModel.C4,
        patch: ModelPatch.AddRelationship,
    ): AnyKumlModel.C4 {
        val newRel =
            C4Relationship(
                id = patch.relationshipId,
                source = patch.sourceId,
                target = patch.targetId,
                label = patch.payload["label"] ?: patch.payload["description"] ?: "",
                technology = patch.payload["technology"],
                description = patch.payload["description"],
            )
        return model.copy(model = model.model.copy(relationships = model.model.relationships + newRel))
    }
}
