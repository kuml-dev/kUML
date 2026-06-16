package dev.kuml.ai.tools.internal

import dev.kuml.uml.Visibility

/**
 * Converts visibility string codes from @Tool parameters to UML Visibility enums.
 *
 * @Tool parameters use String instead of enum to work around the Koog 0.7.3
 * BasicJsonSchemaGenerator limitation with enum types in tool signatures.
 */
internal object EnumCoercion {
    /**
     * Parses a visibility string to UML Visibility.
     *
     * Accepts: "PUBLIC", "PRIVATE", "PROTECTED", "PACKAGE" (case-insensitive).
     * Returns null for blank/null input (callers should use a default).
     * Throws IllegalArgumentException for non-blank invalid values.
     */
    internal fun toVisibility(s: String?): Visibility? {
        if (s.isNullOrBlank()) return null
        return when (s.uppercase().trim()) {
            "PUBLIC", "+" -> Visibility.PUBLIC
            "PRIVATE", "-" -> Visibility.PRIVATE
            "PROTECTED", "#" -> Visibility.PROTECTED
            "PACKAGE", "~" -> Visibility.PACKAGE
            else -> throw IllegalArgumentException(
                "Unknown visibility '$s'. Valid values: PUBLIC, PRIVATE, PROTECTED, PACKAGE",
            )
        }
    }
}
