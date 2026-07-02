package dev.kuml.core.ocl

import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.PartDefinition

/**
 * Resolves `self.prop` navigations for [PartDefinition] receivers (V3.2.23).
 *
 * Analogous to [UmlPropertyAccessor]'s metamodel-driven attribute lookup:
 * `self.<featureName>` resolves to the declared [KermlFeature] in
 * [PartDefinition.features] whose name matches — the feature object itself is
 * returned (e.g. `self.mass->notEmpty()` where `mass` is a SysML 2 attribute
 * usage on the part).
 *
 * Returns [NOT_FOUND] (rather than throwing) when [self] is not a
 * [PartDefinition] or [prop] has no match, so [PropertyAccessor] can fall
 * through to the next accessor in its dispatch chain.
 */
internal object Sysml2PropertyAccessor {
    internal fun get(
        self: Any,
        prop: String,
    ): Any =
        when {
            self is PartDefinition && prop == "name" -> self.name
            self is PartDefinition && prop == "isAbstract" -> self.isAbstract
            self is PartDefinition && prop == "features" -> self.features
            self is PartDefinition && prop == "specializations" -> self.specializations
            self is PartDefinition ->
                self.features.firstOrNull { it.name == prop } ?: NOT_FOUND
            self is KermlFeature && prop == "name" -> self.name
            self is KermlFeature && prop == "multiplicity" -> self.multiplicity
            self is KermlFeature && prop == "isAbstract" -> self.isAbstract
            self is KermlFeature && prop == "isReadOnly" -> self.isReadOnly
            self is KermlFeature && prop == "typeId" -> self.typeId ?: NOT_FOUND
            self is KermlFeature && prop == "definitionId" -> self.definitionId ?: NOT_FOUND
            else -> NOT_FOUND
        }

    /** Sentinel distinguishing "no SysML 2-specific mapping" from a legitimate `null` navigation result. */
    internal val NOT_FOUND = Any()
}
