package dev.kuml.plugin.examples.cpp

/**
 * Configuration options for [CppCodeGenerator].
 *
 * @property useSmartPointers When `true`, association pointer types use `std::shared_ptr<T>`
 *   instead of raw `T*`.
 * @property generateCpp When `true` (default), a `.cpp` source skeleton is generated alongside
 *   every `.hpp` header for classes.
 * @property namespaceStyle How namespace blocks are emitted (nested vs. C++17 flat).
 * @property naming Naming convention applied to member variable names and method names.
 * @property namespaceName Optional C++ namespace to wrap all generated code in.
 *   `null` means no namespace wrapper is emitted.
 */
public data class CppGeneratorOptions(
    val useSmartPointers: Boolean = false,
    val generateCpp: Boolean = true,
    val namespaceStyle: CppNamespaceStyle = CppNamespaceStyle.NESTED,
    val naming: CppNamingConvention = CppNamingConvention.DEFAULT,
    val namespaceName: String? = null,
) {
    public companion object {
        /** Parses generator options from a string-keyed options map. */
        public fun from(options: Map<String, String>): CppGeneratorOptions =
            CppGeneratorOptions(
                useSmartPointers = options["useSmartPointers"]?.lowercase() == "true",
                generateCpp = options["generateCpp"]?.lowercase()?.let { it != "false" } ?: true,
                namespaceStyle =
                    when (options["namespaceStyle"]?.lowercase()) {
                        "flat" -> CppNamespaceStyle.FLAT
                        else -> CppNamespaceStyle.NESTED
                    },
                naming =
                    when (options["naming"]?.lowercase()) {
                        "snake_case" -> CppNamingConvention.SNAKE_CASE
                        else -> CppNamingConvention.DEFAULT
                    },
                namespaceName = options["namespace"]?.takeIf { it.isNotBlank() },
            )
    }
}
