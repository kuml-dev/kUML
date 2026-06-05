package dev.kuml.io.svg.uml

import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility

/** Formatiert eine [UmlProperty] als `+ name: Type`. */
internal fun UmlProperty.format(): String {
    val vis = visibility.symbol()
    val typeLabel = type.name
    return "$vis $name: $typeLabel"
}

/** Formatiert eine [UmlOperation] als `+ name(params): ReturnType`. */
internal fun UmlOperation.format(): String {
    val vis = visibility.symbol()
    val params =
        parameters
            .filter { it.direction != ParameterDirection.RETURN }
            .joinToString(", ") { "${it.name}: ${it.type.name}" }
    val ret = returnType?.name?.let { ": $it" } ?: ""
    return "$vis $name($params)$ret"
}

/** Gibt das Sichtbarkeits-Symbol zurück. */
internal fun Visibility.symbol(): String =
    when (this) {
        Visibility.PUBLIC -> "+"
        Visibility.PRIVATE -> "-"
        Visibility.PROTECTED -> "#"
        Visibility.PACKAGE -> "~"
    }
