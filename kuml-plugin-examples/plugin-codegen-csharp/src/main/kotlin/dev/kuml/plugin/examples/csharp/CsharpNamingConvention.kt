package dev.kuml.plugin.examples.csharp

/**
 * Naming convention to apply to C# property names and method names.
 *
 * NOTE: This convention is NEVER applied to type/class names — those always stay PascalCase.
 */
public enum class CsharpNamingConvention {
    /** Passthrough — names are used as-is. */
    DEFAULT,

    /**
     * Converts the first character of each name to uppercase (PascalCase).
     *
     * This matches the idiomatic C# property naming convention where properties
     * are PascalCase (e.g. `Name`, `TotalAmount`).
     */
    PASCAL_CASE,
    ;

    /** Applies this naming convention to [name]. */
    public fun apply(name: String): String =
        when (this) {
            DEFAULT -> name
            PASCAL_CASE ->
                if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
        }
}
