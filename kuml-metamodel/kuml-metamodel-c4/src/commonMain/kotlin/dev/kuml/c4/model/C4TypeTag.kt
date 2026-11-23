package dev.kuml.c4.model

/**
 * The bracketed type tag every renderer shows for a C4 element (PAL wave 2G-b), the single source for the SVG renderer,
 * the layout size provider and the Kuiver renderer, so measured and drawn text can not drift apart.
 *
 * - [C4Person] -> `[Person]`, [C4SoftwareSystem] -> `[Software System]`
 * - [C4Container] -> `[Container: <technology>]`, [C4Component] -> `[Component: <technology>]`; without technology the
 *   colon is left out
 * - an external person or system gets the prefix `External `, e.g. `[External Software System]`
 * - a non-blank `typeLabel` on a [C4Person] or [C4SoftwareSystem] replaces the derived tag completely
 *   (`typeLabel = "Dokument"` -> `[Dokument]`), trimmed and capped at [C4_TYPE_LABEL_MAX_LENGTH] characters
 */
fun c4TypeTag(element: C4Element): String {
    customTypeLabel(element)?.let { return "[$it]" }
    return derivedTypeTag(element)
}

/** Maximum length of a custom `typeLabel`; longer labels are cut (DoS/layout guard). */
const val C4_TYPE_LABEL_MAX_LENGTH: Int = 80

private fun customTypeLabel(element: C4Element): String? =
    when (element) {
        is C4Person -> element.typeLabel
        is C4SoftwareSystem -> element.typeLabel
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }?.take(C4_TYPE_LABEL_MAX_LENGTH)

private fun derivedTypeTag(element: C4Element): String =
    when (element) {
        is C4Person -> "[${externalPrefix(element.external)}Person]"
        is C4SoftwareSystem -> "[${externalPrefix(element.external)}Software System]"
        is C4Container -> bracket(kind = "Container", technology = element.technology)
        is C4Component -> bracket(kind = "Component", technology = element.technology)
        is C4DeploymentNode -> bracket(kind = "Deployment Node", technology = element.technology)
        else -> "[${element.name}]"
    }

/** `true` when [element] carries a non-blank custom `typeLabel` (see [c4TypeTag]). */
fun c4HasCustomTypeLabel(element: C4Element): Boolean = customTypeLabel(element) != null

/** `true` when [element] is an external person or system. */
fun c4IsExternal(element: C4Element): Boolean =
    when (element) {
        is C4Person -> element.external
        is C4SoftwareSystem -> element.external
        else -> false
    }

private fun externalPrefix(external: Boolean): String = if (external) "External " else ""

private fun bracket(
    kind: String,
    technology: String?,
): String = if (technology.isNullOrBlank()) "[$kind]" else "[$kind: $technology]"
