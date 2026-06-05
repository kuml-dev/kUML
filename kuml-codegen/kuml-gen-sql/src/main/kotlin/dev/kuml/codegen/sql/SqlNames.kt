package dev.kuml.codegen.sql

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue

internal object SqlNames {
    /** Tabellen-Name aus Klasse — nutzt «Entity»{tableName=…} falls vorhanden, sonst lowercase + 's'. */
    fun tableName(
        className: String,
        stereotypes: List<AppliedStereotype>,
    ): String {
        val explicit =
            stereotypes
                .firstOrNull { it.stereotypeName == "Entity" }
                ?.tags
                ?.get("tableName")
                ?.stringOrNull()
        return explicit ?: pluralize(className.lowercase())
    }

    /** Spalten-Name aus Property — nutzt «Column»{name=…} falls vorhanden, sonst snake_case. */
    fun columnName(
        propertyName: String,
        stereotypes: List<AppliedStereotype>,
    ): String {
        val explicit =
            stereotypes
                .firstOrNull { it.stereotypeName == "Column" }
                ?.tags
                ?.get("name")
                ?.stringOrNull()
        return explicit ?: camelToSnake(propertyName)
    }

    /** Simple Pluralisierung — V1.1.4 unterstützt nur reguläre Plurals. */
    fun pluralize(s: String): String =
        when {
            s.endsWith("s") || s.endsWith("x") || s.endsWith("ch") -> "${s}es"
            s.endsWith("y") -> s.dropLast(1) + "ies"
            else -> "${s}s"
        }

    /** Konvertiert camelCase / PascalCase nach snake_case. */
    fun camelToSnake(s: String): String =
        Regex("([a-z])([A-Z])")
            .replace(s) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .lowercase()

    private fun TagValue.stringOrNull(): String? = (this as? TagValue.StringVal)?.v
}
