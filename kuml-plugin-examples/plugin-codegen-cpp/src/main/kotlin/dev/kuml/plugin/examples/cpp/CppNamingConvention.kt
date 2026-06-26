package dev.kuml.plugin.examples.cpp

/**
 * Naming convention to apply to C++ member variable names and method names.
 *
 * NOTE: This convention is NEVER applied to type/class names — those always stay PascalCase.
 */
public enum class CppNamingConvention {
    /** Passthrough — names are used as-is. */
    DEFAULT,

    /**
     * Converts camelCase or PascalCase identifiers to snake_case.
     *
     * Inserts an underscore before each uppercase letter that follows a lowercase letter
     * or digit, then lowercases the entire string.
     */
    SNAKE_CASE,
    ;

    /** Applies this naming convention to [name]. */
    public fun apply(name: String): String =
        when (this) {
            DEFAULT -> name
            SNAKE_CASE ->
                buildString {
                    name.forEachIndexed { i, c ->
                        if (c.isUpperCase() && i > 0 && (name[i - 1].isLowerCase() || name[i - 1].isDigit())) {
                            append('_')
                        }
                        append(c.lowercaseChar())
                    }
                }
        }
}

/** Style in which C++ namespace blocks are emitted. */
public enum class CppNamespaceStyle {
    /** Emits nested `namespace a { namespace b { ... } }` (C++03 compatible). */
    NESTED,

    /** Emits C++17 compound `namespace a::b { ... }`. */
    FLAT,
}
