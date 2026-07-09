package dev.kuml.transform.umlerm

import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue

/**
 * Looks up an applied stereotype whose [AppliedStereotype.profileNamespace] matches
 * [dev.kuml.profile.erm.ErmMappingProfile]'s namespace and whose name matches
 * [name] case-insensitively.
 *
 * Namespace-scoping avoids collisions with foreign stereotypes of the same literal
 * name (e.g. this profile's `«Entity»` vs. the JavaEE profile's `«Entity»` applied
 * by [dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer]) — see kUML V3.4.6
 * plan, stolperfalle 7.
 */
internal fun List<AppliedStereotype>.ermStereotype(name: String): AppliedStereotype? =
    firstOrNull {
        it.profileNamespace == ErmProfileNames.NAMESPACE && it.stereotypeName.equals(name, ignoreCase = true)
    }

/** `true` if this list contains an applied ERM-namespaced stereotype called [name]. */
internal fun List<AppliedStereotype>.hasErmStereotype(name: String): Boolean = ermStereotype(name) != null

/** Reads tag [key] as a string — unwraps [TagValue.StringVal] or [TagValue.EnumVal.valueName]. */
internal fun AppliedStereotype.stringTag(key: String): String? =
    when (val v = tags[key]) {
        is TagValue.StringVal -> v.v
        is TagValue.EnumVal -> v.valueName
        else -> null
    }

/** Reads tag [key] as a boolean — unwraps [TagValue.BoolVal], or parses a strict boolean [TagValue.StringVal]. */
internal fun AppliedStereotype.boolTag(key: String): Boolean? =
    when (val v = tags[key]) {
        is TagValue.BoolVal -> v.v
        is TagValue.StringVal -> v.v.toBooleanStrictOrNull()
        else -> null
    }
