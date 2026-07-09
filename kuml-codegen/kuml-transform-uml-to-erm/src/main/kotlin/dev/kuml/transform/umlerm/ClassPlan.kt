package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.UmlClass

/**
 * Per-class inheritance-mapping decision, computed once up front by
 * [planInheritance] before any [dev.kuml.erm.model.ErmEntity] is built.
 *
 * @property parentId direct UML generalization parent, if any.
 * @property childIds direct UML generalization children.
 * @property strategy the effective [InheritanceStrategy] for this class's hierarchy
 *   (same value for every class in one connected generalization tree).
 * @property hasOwnTable `true` if this class gets its own [dev.kuml.erm.model.ErmEntity].
 *   `false` for [InheritanceStrategy.SINGLE_TABLE] non-root classes and
 *   [InheritanceStrategy.TABLE_PER_CLASS] abstract classes.
 *
 * V3.4.6
 */
internal class ClassPlan(
    val classId: String,
) {
    var parentId: String? = null
    val childIds: MutableList<String> = mutableListOf()
    var strategy: InheritanceStrategy = InheritanceStrategy.JOINED
    var hasOwnTable: Boolean = true

    val isInHierarchy: Boolean get() = parentId != null || childIds.isNotEmpty()
}

/**
 * Builds a [ClassPlan] for every class, resolving the effective
 * [InheritanceStrategy] per hierarchy (propagated from the root class's
 * `«Inheritance».strategy` override, then [TransformContext.options]`["inheritance"]`,
 * then [InheritanceStrategy.JOINED]) and deciding which classes get their own table.
 *
 * Order matters (V3.4.6 plan, stolperfalle "Reihenfolge"): this must run *before*
 * association FK resolution, because [InheritanceStrategy.SINGLE_TABLE] descendants
 * have no entity of their own — any FK pointing at one must be redirected to the
 * nearest table-owning ancestor (see `physicalEntityIdOf` in [UmlToErmTransformer]).
 */
internal fun planInheritance(
    classes: List<UmlClass>,
    classById: Map<String, UmlClass>,
    parentOf: Map<String, String>,
    childrenOf: Map<String, List<String>>,
    ctx: TransformContext,
): Map<String, ClassPlan> {
    val plans = classes.associate { it.id to ClassPlan(it.id) }

    for (cls in classes) {
        val plan = plans.getValue(cls.id)
        plan.parentId = parentOf[cls.id]
        plan.childIds += childrenOf[cls.id].orEmpty()
    }

    fun rootIdOf(classId: String): String {
        var cur = classId
        val seen = mutableSetOf<String>()
        while (true) {
            if (!seen.add(cur)) return cur // cycle guard — should not happen in a well-formed model
            val parent = plans[cur]?.parentId ?: return cur
            cur = parent
        }
    }

    for (cls in classes) {
        val plan = plans.getValue(cls.id)
        if (!plan.isInHierarchy) continue
        val rootId = rootIdOf(cls.id)
        val rootCls = classById[rootId] ?: continue
        plan.strategy = resolveStrategy(rootCls, ctx)
    }

    for (cls in classes) {
        val plan = plans.getValue(cls.id)
        plan.hasOwnTable =
            when {
                !plan.isInHierarchy -> true
                plan.strategy == InheritanceStrategy.SINGLE_TABLE -> plan.parentId == null
                plan.strategy == InheritanceStrategy.TABLE_PER_CLASS -> !cls.isAbstract
                else -> true // JOINED
            }
    }

    return plans
}

private fun resolveStrategy(
    rootCls: UmlClass,
    ctx: TransformContext,
): InheritanceStrategy {
    val tagStrategy = rootCls.appliedStereotypes.ermStereotype(ErmProfileNames.INHERITANCE)?.stringTag(ErmProfileNames.TAG_STRATEGY)
    return InheritanceStrategy.fromTag(tagStrategy)
        ?: InheritanceStrategy.fromTag(ctx.options["inheritance"])
        ?: InheritanceStrategy.JOINED
}

/** The `«Inheritance».discriminatorColumn` override for [rootCls], or `"dtype"`. */
internal fun discriminatorColumnNameOf(rootCls: UmlClass): String =
    rootCls.appliedStereotypes.ermStereotype(ErmProfileNames.INHERITANCE)?.stringTag(ErmProfileNames.TAG_DISCRIMINATOR_COLUMN)
        ?: "dtype"
