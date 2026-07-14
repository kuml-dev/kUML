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

/**
 * All applied ERM-namespaced stereotypes called [name] — unlike [ermStereotype], does not stop
 * at the first match. Needed for repeatable stereotypes (e.g. `«Index»`, applied once per index
 * a class needs) where [ermStereotype]'s `firstOrNull` would silently drop every application
 * after the first.
 */
internal fun List<AppliedStereotype>.ermStereotypes(name: String): List<AppliedStereotype> =
    filter { it.profileNamespace == ErmProfileNames.NAMESPACE && it.stereotypeName.equals(name, ignoreCase = true) }

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

/** Reads tag [key] as a list of strings — unwraps [TagValue.ListVal], dropping any non-string item. */
internal fun AppliedStereotype.listTag(key: String): List<String>? =
    (tags[key] as? TagValue.ListVal)?.items?.mapNotNull { (it as? TagValue.StringVal)?.v }
