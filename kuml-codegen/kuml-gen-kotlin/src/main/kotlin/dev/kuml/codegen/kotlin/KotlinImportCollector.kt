package dev.kuml.codegen.kotlin

/** Returns the fully-qualified import string for types that need one, or null for Kotlin built-ins. */
internal object KotlinImportCollector {
    private val IMPORTS: Map<String, String> =
        mapOf(
            "UUID" to "java.util.UUID",
            "Date" to "java.time.LocalDate",
            "LocalDate" to "java.time.LocalDate",
            "DateTime" to "java.time.LocalDateTime",
            "LocalDateTime" to "java.time.LocalDateTime",
            "Instant" to "java.time.Instant",
            "BigDecimal" to "java.math.BigDecimal",
        )

    internal fun collectForType(typeName: String): String? = IMPORTS[typeName]
}
