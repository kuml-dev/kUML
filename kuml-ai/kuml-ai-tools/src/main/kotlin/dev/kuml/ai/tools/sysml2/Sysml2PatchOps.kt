package dev.kuml.ai.tools.sysml2

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UseCaseDefinition

/**
 * Pure mutation functions for SysML 2 model editing.
 */
internal object Sysml2PatchOps {
    internal fun addPartDef(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
    ): AnyKumlModel.Sysml2 {
        val partDef = PartDefinition(id = id, name = name)
        return model.copy(model = model.model.copy(definitions = model.model.definitions + partDef))
    }

    internal fun addAttributeDef(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
        ownerId: String,
        typeName: String,
        unit: String?,
    ): AnyKumlModel.Sysml2 {
        // Create the attribute definition at the top level
        val attrDef = AttributeDefinition(id = id, name = name)
        // Also create an AttributeUsage nested in the owning PartDefinition
        val usageId = "${ownerId}_$id"
        val usage =
            AttributeUsage(
                id = usageId,
                name = name,
                qualifiedName = "$ownerId.$name",
                definitionId = id,
                defaultExpression = unit,
            )
        val newDefs = model.model.definitions + attrDef
        val newUsages = model.model.usages + usage
        return model.copy(model = model.model.copy(definitions = newDefs, usages = newUsages))
    }

    internal fun addState(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
        isInitial: Boolean,
        isFinal: Boolean,
    ): AnyKumlModel.Sysml2 {
        val state = StateDefinition(id = id, name = name, isInitial = isInitial, isFinal = isFinal)
        return model.copy(model = model.model.copy(definitions = model.model.definitions + state))
    }

    internal fun addTransition(
        model: AnyKumlModel.Sysml2,
        id: String,
        sourceId: String,
        targetId: String,
        trigger: String,
        guard: String?,
        action: String?,
    ): AnyKumlModel.Sysml2 {
        val transition =
            TransitionUsage(
                id = id,
                name = "${trigger}_transition",
                sourceStateId = sourceId,
                targetStateId = targetId,
                trigger = trigger,
                guard = guard,
                effect = action,
            )
        return model.copy(model = model.model.copy(usages = model.model.usages + transition))
    }

    internal fun addUseCase(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
        actorId: String?,
        actorName: String?,
    ): AnyKumlModel.Sysml2 {
        val uc = UseCaseDefinition(id = id, name = name)
        var newDefs = model.model.definitions + uc
        var newDiagrams = model.model.diagrams

        // Create actor if actorName is provided and actorId is not yet in the model
        val resolvedActorId =
            actorId ?: if (actorName != null) {
                val existingActor =
                    model.model.definitions
                        .filterIsInstance<ActorDefinition>()
                        .firstOrNull { it.name.equals(actorName, ignoreCase = true) }
                if (existingActor == null) {
                    val newActorId = "actor_${actorName.lowercase().replace(" ", "_")}"
                    val actor = ActorDefinition(id = newActorId, name = actorName)
                    newDefs = newDefs + actor
                    newActorId
                } else {
                    existingActor.id
                }
            } else {
                null
            }

        return model.copy(model = model.model.copy(definitions = newDefs, diagrams = newDiagrams))
    }

    internal fun addRequirement(
        model: AnyKumlModel.Sysml2,
        id: String,
        reqId: String,
        text: String,
    ): AnyKumlModel.Sysml2 {
        val req =
            RequirementDefinition(
                id = id,
                name = reqId,
                reqId = reqId,
                text = text,
            )
        return model.copy(model = model.model.copy(definitions = model.model.definitions + req))
    }

    internal fun addAction(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
        kind: ActivityNodeKind,
    ): AnyKumlModel.Sysml2 {
        val action = ActionDefinition(id = id, name = name, kind = kind)
        return model.copy(model = model.model.copy(definitions = model.model.definitions + action))
    }

    internal fun addConstraint(
        model: AnyKumlModel.Sysml2,
        id: String,
        name: String,
        expression: String,
    ): AnyKumlModel.Sysml2 {
        val constraint = ConstraintDefinition(id = id, name = name, expression = expression)
        return model.copy(model = model.model.copy(definitions = model.model.definitions + constraint))
    }

    internal fun resolveDefinition(
        model: AnyKumlModel.Sysml2,
        idOrName: String,
    ): Sysml2Definition? =
        model.model.definitions.firstOrNull { it.id == idOrName }
            ?: model.model.definitions.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }

    internal fun allIds(model: AnyKumlModel.Sysml2): Set<String> =
        (model.model.definitions.map { it.id } + model.model.usages.map { it.id }).toSet()
}
